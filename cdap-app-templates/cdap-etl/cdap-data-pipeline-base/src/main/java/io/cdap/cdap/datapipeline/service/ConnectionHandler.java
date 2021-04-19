/*
 * Copyright © 2021 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package io.cdap.cdap.datapipeline.service;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.cdap.cdap.api.plugin.PluginPropertyField;
import io.cdap.cdap.api.service.http.HttpServiceRequest;
import io.cdap.cdap.api.service.http.HttpServiceResponder;
import io.cdap.cdap.api.service.http.SystemHttpServiceContext;
import io.cdap.cdap.datapipeline.connection.Connection;
import io.cdap.cdap.datapipeline.connection.ConnectionCreationRequest;
import io.cdap.cdap.datapipeline.connection.ConnectionId;
import io.cdap.cdap.datapipeline.connection.ConnectionStore;
import io.cdap.cdap.etl.api.connector.ExploreDetail;
import io.cdap.cdap.etl.api.connector.ExploreEntity;
import io.cdap.cdap.etl.api.connector.ExploreEntityProperty;
import io.cdap.cdap.proto.id.NamespaceId;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

/**
 * Handler for all the connection operations
 */
public class ConnectionHandler extends AbstractDataPipelineHandler {
  private static final String API_VERSION = "v1";
  private static final Gson GSON = new GsonBuilder()
    .setPrettyPrinting()
    .create();
  private ConnectionStore store;

  @Override
  public void initialize(SystemHttpServiceContext context) throws Exception {
    super.initialize(context);
    store = new ConnectionStore(context);
  }

  /**
   * Returns the list of connections in the given namespace
   */
  @GET
  @Path(API_VERSION + "/contexts/{context}/connections")
  public void listConnections(HttpServiceRequest request, HttpServiceResponder responder,
                              @PathParam("context") String namespace) {
    respond(namespace, responder, namespaceSummary -> {
      if (namespaceSummary.getName().toLowerCase().equals(NamespaceId.SYSTEM.getNamespace())) {
        responder.sendError(HttpURLConnection.HTTP_BAD_REQUEST,
                            "Listing connections in system namespace is currently not supported");
        return;
      }
      responder.sendJson(store.listConnections(namespaceSummary));
    });
  }

  /**
   * Returns the specific connection information in the given namespace
   */
  @GET
  @Path(API_VERSION + "/contexts/{context}/connections/{connection}")
  public void getConnection(HttpServiceRequest request, HttpServiceResponder responder,
                            @PathParam("context") String namespace,
                            @PathParam("connection") String connection) {
    respond(namespace, responder, namespaceSummary -> {
      if (namespaceSummary.getName().toLowerCase().equals(NamespaceId.SYSTEM.getNamespace())) {
        responder.sendError(HttpURLConnection.HTTP_BAD_REQUEST,
                            "Getting connection in system namespace is currently not supported");
        return;
      }
      responder.sendJson(store.getConnection(new ConnectionId(namespaceSummary, connection)));
    });
  }

  /**
   * Creates a connection in the given namespace
   */
  @PUT
  @Path(API_VERSION + "/contexts/{context}/connections/{connection}")
  public void createConnection(HttpServiceRequest request, HttpServiceResponder responder,
                               @PathParam("context") String namespace,
                               @PathParam("connection") String connection) {
    respond(namespace, responder, namespaceSummary -> {
      if (namespaceSummary.getName().toLowerCase().equals(NamespaceId.SYSTEM.getNamespace())) {
        responder.sendError(HttpURLConnection.HTTP_BAD_REQUEST,
                            "Creating connection in system namespace is currently not supported");
        return;
      }

      ConnectionCreationRequest creationRequest =
        GSON.fromJson(StandardCharsets.UTF_8.decode(request.getContent()).toString(), ConnectionCreationRequest.class);

      Connection connectionInfo = new Connection(connection, creationRequest.getPlugin().getName(),
                                                 creationRequest.getDescription(), false,
                                                 System.currentTimeMillis(),
                                                 creationRequest.getPlugin());
      store.saveConnection(new ConnectionId(namespaceSummary, connection), connectionInfo);
      responder.sendStatus(HttpURLConnection.HTTP_OK);
    });
  }

  /**
   * Delete a connection in the given namespace
   */
  @DELETE
  @Path(API_VERSION + "/contexts/{context}/connections/{connection}")
  public void deleteConnection(HttpServiceRequest request, HttpServiceResponder responder,
                               @PathParam("context") String namespace,
                               @PathParam("connection") String connection) {
    respond(namespace, responder, namespaceSummary -> {
      if (namespaceSummary.getName().toLowerCase().equals(NamespaceId.SYSTEM.getNamespace())) {
        responder.sendError(HttpURLConnection.HTTP_BAD_REQUEST,
                            "Creating connection in system namespace is currently not supported");
        return;
      }

      store.deleteConnection(new ConnectionId(namespaceSummary, connection));
      responder.sendStatus(HttpURLConnection.HTTP_OK);
    });
  }

  @GET
  @Path(API_VERSION + "/contexts/{context}/connections/{connection}/explore")
  public void explore(HttpServiceRequest request, HttpServiceResponder responder,
                      @PathParam("context") String namespace,
                      @PathParam("connection") String connection,
                      @QueryParam("path") @DefaultValue("") String path,
                      @QueryParam("limit") @DefaultValue("1000") int limit) {
    respond(namespace, responder, namespaceSummary -> {
      if (namespaceSummary.getName().toLowerCase().equals(NamespaceId.SYSTEM.getNamespace())) {
        responder.sendError(HttpURLConnection.HTTP_BAD_REQUEST,
                            "Getting connection in system namespace is currently not supported");
        return;
      }

      Connection connection1 = null;
      try {
        connection1 = store.getConnection(new ConnectionId(namespaceSummary, connection));
      } catch (Exception e) {
        // ignore
      }

      List<ExploreEntity> entities = new ArrayList<>();
      if (connection1 != null && connection1.getName().toLowerCase().contains("bigquery")) {
        // reach dataset
        if (!path.toLowerCase().contains("mydataset")) {
          for (int i = 0; i < 10; i++) {
            ExploreEntity entity = new ExploreEntity("mydataset" + i, "mydataset" + i, "dataset", false, true,
                                                     Collections.emptyList());
            entities.add(entity);
          }
          ExploreDetail detail = new ExploreDetail(entities.size(), Collections.emptyMap(), entities);
          responder.sendJson(detail);
          return;
        }

        // reach table
        for (int i = 0; i < 10; i++) {
          ExploreEntity entity = new ExploreEntity("mytable" + i, path + "/mytable" + i, "table", true, false,
                                                   Collections.emptyList());
          entities.add(entity);
        }
        ExploreDetail detail = new ExploreDetail(entities.size(), Collections.emptyMap(), entities);
        responder.sendJson(detail);
        return;
      }

      // reach gcs bucket
      if (!path.toLowerCase().contains("mybucket")) {
        for (int i = 0; i < 10; i++) {
          ExploreEntity entity = new ExploreEntity(
            "mybucket" + i, "mybucket" + i, "directory", true, true,
            ImmutableList.of(new ExploreEntityProperty("Size", 1000, "Long"),
                             new ExploreEntityProperty("Created", System.currentTimeMillis(), "Timestamp")));
          entities.add(entity);
        }
        ExploreDetail detail = new ExploreDetail(entities.size(), Collections.emptyMap(), entities);
        responder.sendJson(detail);
        return;
      }

      // reach blob
      if (!path.toLowerCase().contains("myblob")) {
        for (int i = 0; i < 10; i++) {
          ExploreEntity entity = new ExploreEntity(
            "myblob" + i, path + "/myblob" + i, "directory", true, true,
            ImmutableList.of(new ExploreEntityProperty("Size", 1000, "Long"),
                             new ExploreEntityProperty("Created", System.currentTimeMillis(), "Timestamp")));
          entities.add(entity);
        }
        ExploreDetail detail = new ExploreDetail(entities.size(), Collections.emptyMap(), entities);
        responder.sendJson(detail);
        return;
      }

      // reach file
      for (int i = 0; i < 10; i++) {
        ExploreEntity entity = new ExploreEntity(
          "myfile" + i, path + "/myfile" + i, "file", true, false,
          ImmutableList.of(new ExploreEntityProperty("Size", 1000, "Long"),
                           new ExploreEntityProperty("Created", System.currentTimeMillis(), "Timestamp")));
        entities.add(entity);
      }

      ExploreDetail detail = new ExploreDetail(
        entities.size(),
        Collections.singletonMap("file",
                                 Collections.singleton(new PluginPropertyField("format", "", "String",
                                                                               true, false))), entities);
      responder.sendJson(detail);
      return;
    });
  }
}
