/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.connectors.jdbc.internal.cli;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import org.apache.geode.annotations.Experimental;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.connectors.jdbc.internal.JdbcConnectorService;
import org.apache.geode.connectors.jdbc.internal.RegionMapping;
import org.apache.geode.management.internal.security.ResourcePermissions;
import org.apache.geode.security.ResourcePermission;

@Experimental
public class ListMappingFunction extends JdbcCliFunction<Void, RegionMapping[]> {

  ListMappingFunction() {
    super(new FunctionContextArgumentProvider(), new ExceptionHandler());
  }

  @Override
  RegionMapping[] getFunctionResult(JdbcConnectorService service, FunctionContext<Void> context) {
    return getRegionMappingsAsArray(service);
  }

  RegionMapping[] getRegionMappingsAsArray(JdbcConnectorService service) {
    Set<RegionMapping> regionMappings = getRegionMappings(service);
    return regionMappings.toArray(new RegionMapping[regionMappings.size()]);
  }

  private Set<RegionMapping> getRegionMappings(JdbcConnectorService service) {
    return service.getRegionMappings();
  }

  @Override
  public Collection<ResourcePermission> getRequiredPermissions(String regionName) {
    return Collections.singletonList(ResourcePermissions.CLUSTER_READ);
  }
}
