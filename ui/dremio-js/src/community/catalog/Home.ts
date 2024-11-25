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

import type { SonarV3Config } from "../../_internal/types/Config.js";
import type { CatalogObjectMethods } from "../../interfaces/CatalogObject.js";
import { catalogReferenceFromProperties } from "./catalogReferenceFromProperties.js";
import { catalogReferenceEntityToProperties, pathString } from "./utils.js";

export class Home implements CatalogObjectMethods {
  #children: ReturnType<typeof catalogReferenceFromProperties>[];
  /**
   * @deprecated
   */
  readonly id: string;
  readonly name: string;
  constructor(properties: any) {
    this.#children = properties.children;
    this.id = properties.id;
    this.name = properties.name;
  }

  children() {
    const c = this.#children;
    return {
      async *data() {
        yield* c;
      },
    };
  }

  get path() {
    return [this.name];
  }

  pathString = pathString(() => this.path);

  get referenceType() {
    return "HOME" as const;
  }

  static fromResource(properties: any, config: SonarV3Config) {
    return new Home({
      children: properties.children.map((child: unknown) =>
        catalogReferenceFromProperties(
          catalogReferenceEntityToProperties(child),
          config,
        ),
      ),
      id: properties.id,
      name: properties.name,
    });
  }
}