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
package com.dremio.exec.store.hive;

import java.io.IOException;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;

import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.dfs.AsyncStreamConf;
import com.dremio.exec.store.dfs.FormatPlugin;
import com.dremio.exec.store.iceberg.IcebergFormatConfig;
import com.dremio.exec.store.iceberg.IcebergFormatPlugin;
import com.dremio.exec.store.iceberg.SupportsIcebergRootPointer;
import com.dremio.io.file.FileSystem;
import com.dremio.sabot.exec.context.OperatorContext;

/**
 * Base class which all hive storage plugins extend
 */
public abstract class BaseHiveStoragePlugin implements SupportsIcebergRootPointer {
  private final SabotContext sabotContext;
  private final String name;

  protected BaseHiveStoragePlugin(SabotContext sabotContext, String pluginName) {
    this.sabotContext = sabotContext;
    this.name = pluginName;
  }

  protected String getName() {
    return name;
  }

  protected SabotContext getSabotContext() {
    return sabotContext;
  }

  public FileSystem createFS(FileSystem fs, OperatorContext operatorContext, AsyncStreamConf cacheAndAsyncConf) throws IOException {
    return this.sabotContext.getFileSystemWrapper().wrap(fs, this.getName(), cacheAndAsyncConf,
        operatorContext, cacheAndAsyncConf.isAsyncEnabled(), false);
  }

  protected final void runQuery(final String query, final String userName, final String queryType) throws Exception {
    sabotContext.getJobsRunner().get().runQueryAsJob(query, userName, queryType);
  }

  @Override
  public Configuration getFsConfCopy() {
    Configuration conf = new Configuration();
    for (Map.Entry<String, String> property: getConfigProperties()) {
      conf.set(property.getKey(), property.getValue());
    }
    return conf;
  }

  @Override
  public FormatPlugin getFormatPlugin(FormatPluginConfig formatConfig) {
    if (formatConfig instanceof IcebergFormatConfig) {
      IcebergFormatPlugin icebergFormatPlugin = new IcebergFormatPlugin("iceberg", sabotContext, (IcebergFormatConfig) formatConfig, null);
      icebergFormatPlugin.initialize((IcebergFormatConfig) formatConfig, this);
      return icebergFormatPlugin;
    }
    throw new UnsupportedOperationException("Format plugins for non iceberg use cases are not supported.");
  }

  public abstract Iterable<Map.Entry<String, String>> getConfigProperties();
}