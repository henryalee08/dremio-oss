/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.maestro;

import java.util.Collection;
import java.util.Map;
import java.util.Queue;

import javax.inject.Provider;

import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.proto.CoordExecRPC.NodeQueryCompletion;
import com.dremio.exec.proto.CoordExecRPC.NodeQueryFirstError;
import com.dremio.exec.proto.CoordExecRPC.NodeQueryScreenCompletion;
import com.dremio.exec.proto.CoordExecRPC.RpcType;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.rpc.Acks;
import com.dremio.exec.rpc.Response;
import com.dremio.exec.rpc.ResponseSender;
import com.dremio.exec.rpc.UserRpcException;
import com.dremio.service.Pointer;
import com.dremio.service.conduit.client.ConduitProvider;
import com.dremio.service.jobresults.JobResultsRequest;
import com.dremio.service.jobresults.JobResultsResponse;
import com.dremio.service.jobresults.JobResultsServiceGrpc;
import com.dremio.service.maestroservice.MaestroServiceGrpc;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;

import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

/**
 * checks and forwards the request to target maestro server
 */
public class MaestroForwarderImpl implements MaestroForwarder {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MaestroForwarderImpl.class);

  private final Provider<ConduitProvider> conduitProvider;
  private final Provider<CoordinationProtos.NodeEndpoint> selfEndpointProvider;
  private final Provider<Collection<CoordinationProtos.NodeEndpoint>> jobServiceInstances;
  private static final Map<String, StreamObserver> resultForwardingStreams = Maps.newConcurrentMap();
  private static final Map<String, Queue<ResponseSender>> responseSenderQueueForQuery =
    Maps.newConcurrentMap();

  public MaestroForwarderImpl(
    final Provider<ConduitProvider> conduitProvider,
    final Provider<CoordinationProtos.NodeEndpoint> nodeEndpointProvider,
    final Provider<Collection<CoordinationProtos.NodeEndpoint>> jobServiceInstances) {

    this.conduitProvider = conduitProvider;
    this.selfEndpointProvider = nodeEndpointProvider;
    this.jobServiceInstances = jobServiceInstances;
  }

  public void screenCompleted(NodeQueryScreenCompletion completion) {
    if (mustForwardRequest(completion.getForeman())) {
      logger.debug("Forwarding NodeQueryScreenCompletion request for Query {} from {} to target {}",
        QueryIdHelper.getQueryId(completion.getId()), completion.getEndpoint().getAddress(), completion.getForeman().getAddress());

      final ManagedChannel channel = conduitProvider.get().getOrCreateChannel(completion.getForeman());
      final MaestroServiceGrpc.MaestroServiceBlockingStub stub = MaestroServiceGrpc.newBlockingStub(channel);
      stub.screenComplete(completion);
    } else {
      logger.warn("screen completion message arrived post query termination, dropping. Query [{}] from node {}.",
        QueryIdHelper.getQueryId(completion.getId()), completion.getEndpoint());
    }
  }

  public void nodeQueryCompleted(NodeQueryCompletion completion) {
    if (mustForwardRequest(completion.getForeman())) {
      logger.debug("Forwarding NodeQueryCompletion request for Query {} from {} to target {}",
        QueryIdHelper.getQueryId(completion.getId()), completion.getEndpoint().getAddress(), completion.getForeman().getAddress());

      final ManagedChannel channel = conduitProvider.get().getOrCreateChannel(completion.getForeman());
      final MaestroServiceGrpc.MaestroServiceBlockingStub stub = MaestroServiceGrpc.newBlockingStub(channel);
      stub.nodeQueryComplete(completion);
    } else {
      logger.warn("A node query completion message arrived post query termination, dropping. Query [{}] from node {}.",
        QueryIdHelper.getQueryId(completion.getId()), completion.getEndpoint());
    }
  }

  public void nodeQueryMarkFirstError(NodeQueryFirstError error) {
    if (mustForwardRequest(error.getForeman())) {
      logger.debug("Forwarding NodeQueryFirstError request for Query {} from {} to target {}",
        QueryIdHelper.getQueryId(error.getHandle().getQueryId()), error.getEndpoint().getAddress(), error.getForeman().getAddress());

      final ManagedChannel channel = conduitProvider.get().getOrCreateChannel(error.getForeman());
      final MaestroServiceGrpc.MaestroServiceBlockingStub stub = MaestroServiceGrpc.newBlockingStub(channel);
      stub.nodeFirstError(error);
    } else {
      logger.warn("A node query error message arrived post query termination, dropping. Query [{}] from node {}.",
        QueryIdHelper.getQueryId(error.getHandle().getQueryId()), error.getEndpoint());
    }
  }

  public void dataArrived(JobResultsRequest jobResultsRequest, ResponseSender sender) {
    String queryId = QueryIdHelper.getQueryId(jobResultsRequest.getHeader().getQueryId());
    if (mustForwardRequest(jobResultsRequest.getForeman())) {
      logger.debug("Forwarding DataArrived request for Query {} to target {}",
        queryId, jobResultsRequest.getForeman().getAddress());
      StreamObserver<JobResultsRequest> streamObserver =
        resultForwardingStreams.computeIfAbsent(queryId, k -> {
          responseSenderQueueForQuery.put(queryId, Queues.newConcurrentLinkedQueue());
          final ManagedChannel channel = conduitProvider.get().getOrCreateChannel(jobResultsRequest.getForeman());
          final JobResultsServiceGrpc.JobResultsServiceStub stub = JobResultsServiceGrpc.newStub(channel);
          Pointer<StreamObserver<JobResultsRequest>> streamObserverInternal = new Pointer<>();
          Context.current().fork().run( () -> {
            streamObserverInternal.value = stub.jobResults(new StreamObserver<JobResultsResponse>() {
            private final String queryIdForStream = queryId;
            @Override
            public void onNext(JobResultsResponse value) {
              ackit(queryIdForStream);
            }

            @Override
            public void onError(Throwable t) {
              try {
                logger.error("Failed to forward job results request to {} for queryId {}",
                  jobResultsRequest.getForeman().getAddress(), queryId, t);
                cleanupQuery(queryId);
                // ok to use any sender since they all point to the same response observer.
                sender.sendFailure(new UserRpcException(jobResultsRequest.getForeman(), "Failed to forward job results request", t));
              } catch (IllegalStateException e) {
                // if the "sender" has illegal state, then no point of sending ack. So ignore the exception.
                logger.debug("Exception raised while acking onError for JobResultsResponse: {}", e);
              }
            }

            @Override
            public void onCompleted() {
              // we initiated the closure. nothing to do.
              logger.info("Forwarding for query {} complete.", queryId);
            }
          });
            });
          return streamObserverInternal.value;
        });

      // make sure we are writing results
      // sequentially
      synchronized (streamObserver) {
        responseSenderQueueForQuery.get(queryId).add(sender);
        streamObserver.onNext(jobResultsRequest);
      }

    } else {
      logger.warn("User data arrived post query termination from {}, dropping. Data was from " +
          "QueryId: {}.", jobResultsRequest.getForeman().getAddress(), queryId);
      sender.sendFailure(new UserRpcException(jobResultsRequest.getForeman(), "Failed to forward job results request", new Throwable("Query Already Terminated")));
    }
  }

  private void cleanupQuery(String queryId) {
    resultForwardingStreams.remove(queryId);
    responseSenderQueueForQuery.remove(queryId);
  }

  @Override
  public void resultsCompleted(String queryId) {
    StreamObserver<JobResultsRequest> streamObserver = resultForwardingStreams.get(queryId);
    if (streamObserver != null) {
      streamObserver.onCompleted();
      cleanupQuery(queryId);
    }
  }

  @Override
  public void resultsError(String queryId, Throwable exception) {
    StreamObserver<JobResultsRequest> streamObserver = resultForwardingStreams.get(queryId);
    if (streamObserver != null) {
      streamObserver.onError(exception);
      cleanupQuery(queryId);
    }
  }

  private void ackit(String queryId){
    // response will be in order. pop the sender in order
    // so that we use the right sequence id.
    ResponseSender sender = responseSenderQueueForQuery.get(queryId).remove();
    sender.send(new Response(RpcType.ACK, Acks.OK));
  }

  private boolean mustForwardRequest(final CoordinationProtos.NodeEndpoint endpoint) {
    if (endpoint == null) { //for UTs
      return false;
    }
    /* do not look at coordinator service set before forwarding. if other
      coordinator has flaky zk connection we will block all rpcs to that coordinator
      from executor leading to a query hang.
      we already know the endpoint no harm in directly sending the rpc; if coordinator
      is really not reachable thats ok; we will clean up in orphan jobs
     */
    return !endpoint.equals(selfEndpointProvider.get());
  }
}