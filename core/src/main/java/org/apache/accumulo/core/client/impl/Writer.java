/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.core.client.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.impl.TabletLocator.TabletLocation;
import org.apache.accumulo.core.client.impl.thrift.ThriftSecurityException;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.tabletserver.thrift.ConstraintViolationException;
import org.apache.accumulo.core.tabletserver.thrift.NotServingTabletException;
import org.apache.accumulo.core.tabletserver.thrift.TDurability;
import org.apache.accumulo.core.tabletserver.thrift.TabletClientService;
import org.apache.accumulo.core.trace.Tracer;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.TServiceClient;

import com.google.common.net.HostAndPort;

public class Writer {

  private static final Logger log = Logger.getLogger(Writer.class);

  private ClientContext context;
  private Text table;

  public Writer(ClientContext context, Text table) {
    checkArgument(context != null, "context is null");
    checkArgument(table != null, "table is null");
    this.context = context;
    this.table = table;
  }

  public Writer(ClientContext context, String table) {
    this(context, new Text(table));
  }

  private static void updateServer(ClientContext context, Mutation m, KeyExtent extent, HostAndPort server) throws TException, NotServingTabletException,
      ConstraintViolationException, AccumuloSecurityException {
    checkArgument(m != null, "m is null");
    checkArgument(extent != null, "extent is null");
    checkArgument(server != null, "server is null");
    checkArgument(context != null, "context is null");

    TabletClientService.Iface client = null;
    try {
      client = ThriftUtil.getTServerClient(server, context);
      client.update(Tracer.traceInfo(), context.rpcCreds(), extent.toThrift(), m.toThrift(), TDurability.DEFAULT);
      return;
    } catch (ThriftSecurityException e) {
      throw new AccumuloSecurityException(e.user, e.code);
    } finally {
      ThriftUtil.returnClient((TServiceClient) client);
    }
  }

  public void update(Mutation m) throws AccumuloException, AccumuloSecurityException, ConstraintViolationException, TableNotFoundException {
    checkArgument(m != null, "m is null");

    if (m.size() == 0)
      throw new IllegalArgumentException("Can not add empty mutations");

    while (true) {
      TabletLocation tabLoc = TabletLocator.getLocator(context, table).locateTablet(context, new Text(m.getRow()), false, true);

      if (tabLoc == null) {
        log.trace("No tablet location found for row " + new String(m.getRow(), UTF_8));
        UtilWaitThread.sleep(500);
        continue;
      }

      final HostAndPort parsedLocation = HostAndPort.fromString(tabLoc.tablet_location);
      try {
        updateServer(context, m, tabLoc.tablet_extent, parsedLocation);
        return;
      } catch (NotServingTabletException e) {
        log.trace("Not serving tablet, server = " + parsedLocation);
        TabletLocator.getLocator(context, table).invalidateCache(tabLoc.tablet_extent);
      } catch (ConstraintViolationException cve) {
        log.error("error sending update to " + parsedLocation + ": " + cve);
        // probably do not need to invalidate cache, but it does not hurt
        TabletLocator.getLocator(context, table).invalidateCache(tabLoc.tablet_extent);
        throw cve;
      } catch (TException e) {
        log.error("error sending update to " + parsedLocation + ": " + e);
        TabletLocator.getLocator(context, table).invalidateCache(tabLoc.tablet_extent);
      }

      UtilWaitThread.sleep(500);
    }

  }
}
