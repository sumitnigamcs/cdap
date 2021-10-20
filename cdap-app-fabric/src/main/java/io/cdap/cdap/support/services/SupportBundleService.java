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
 */

package io.cdap.cdap.support.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import io.cdap.cdap.common.NamespaceNotFoundException;
import io.cdap.cdap.common.conf.CConfiguration;
import io.cdap.cdap.common.conf.Constants;
import io.cdap.cdap.common.namespace.RemoteNamespaceQueryClient;
import io.cdap.cdap.common.utils.DirUtils;
import io.cdap.cdap.proto.NamespaceMeta;
import io.cdap.cdap.proto.id.NamespaceId;
import io.cdap.cdap.support.SupportBundleState;
import io.cdap.cdap.support.job.SupportBundleJob;
import io.cdap.cdap.support.status.CollectionState;
import io.cdap.cdap.support.status.SupportBundleConfiguration;
import io.cdap.cdap.support.status.SupportBundleStatus;
import io.cdap.cdap.support.task.factory.SupportBundlePipelineInfoTaskFactory;
import io.cdap.cdap.support.task.factory.SupportBundleSystemLogTaskFactory;
import io.cdap.cdap.support.task.factory.SupportBundleTaskFactory;
import org.apache.twill.common.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Support bundle service to generate base path, uuid and trigger the job to execute tasks
 */
public class SupportBundleService implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(SupportBundleService.class);
  private static final Gson GSON = new GsonBuilder().create();
  private final List<SupportBundleTaskFactory> supportBundleTaskFactoryList;
  private final ExecutorService executorService;
  private final SupportBundleStatus supportBundleStatus;
  private final CConfiguration cConf;
  private final RemoteNamespaceQueryClient namespaceQueryClient;
  private final String localDir;

  @Inject
  public SupportBundleService(CConfiguration cConf, SupportBundleStatus supportBundleStatus,
                              SupportBundleSystemLogTaskFactory supportBundleSystemLogTaskFactory,
                              SupportBundlePipelineInfoTaskFactory supportBundlePipelineInfoTaskFactory,
                              RemoteNamespaceQueryClient namespaceQueryClient,
                              List<SupportBundleTaskFactory> supportBundleTaskFactoryList) {
    this.cConf = cConf;
    this.supportBundleStatus = supportBundleStatus;
    this.namespaceQueryClient = namespaceQueryClient;
    this.supportBundleTaskFactoryList = supportBundleTaskFactoryList;
    this.executorService = Executors.newFixedThreadPool(
        cConf.getInt(Constants.SupportBundle.MAX_THREADS),
        Threads.createDaemonThreadFactory("perform-support-bundle"));
    this.localDir = this.cConf.get(Constants.CFG_SUPPORT_BUNDLE_LOCAL_DATA_DIR);
  }

  /**
   * Generates support bundle
   */
  public String generateSupportBundle(SupportBundleConfiguration supportBundleConfiguration) {
    String uuid = UUID.randomUUID().toString();
    int folderMaxNumber = cConf.getInt(Constants.SupportBundle.MAX_FOLDER_SIZE);
    File basePath = new File(localDir, uuid);
    SupportBundleJob supportBundleJob =
        new SupportBundleJob(
            executorService,
            cConf,
            supportBundleStatus,
            supportBundleTaskFactoryList);
    String namespaceId = supportBundleConfiguration.getNamespaceId();
    SupportBundleState supportBundleState = new SupportBundleState(supportBundleConfiguration);
    try {
      if (namespaceId != null) {
        NamespaceId namespace = new NamespaceId(namespaceId);
        if (!namespaceQueryClient.exists(namespace)) {
          throw new NamespaceNotFoundException(namespace);
        }
      }
      Long startTs = System.currentTimeMillis();
      supportBundleStatus.setBundleId(uuid);
      supportBundleStatus.setStartTimestamp(startTs);

      supportBundleStatus.setParameters(supportBundleConfiguration);

      List<String> namespaceList = new ArrayList<>();
      if (namespaceId == null) {
        namespaceList.addAll(
            namespaceQueryClient.list().stream()
                .map(NamespaceMeta::getName)
                .collect(Collectors.toList()));
      } else {
        namespaceList.add(namespaceId);
      }
      // Puts all the files under the uuid path
      File baseDirectory = new File(localDir);
      DirUtils.mkdirs(baseDirectory);
      int fileCount = DirUtils.list(baseDirectory).size();

      // We want to keep consistent number of bundle to provide to customer
      if (fileCount >= folderMaxNumber) {
        File oldFilesDirectory = getOldestFolder(baseDirectory);
        deleteOldFolders(oldFilesDirectory);
      }
      DirUtils.mkdirs(basePath);
      supportBundleState.setUuid(uuid);
      supportBundleState.setBasePath(basePath.getPath());
      supportBundleState.setNamespaceList(namespaceList);
      supportBundleState.setSupportBundleJob(supportBundleJob);
      executorService.execute(() -> supportBundleJob.generateBundle(supportBundleState));
    } catch (Exception e) {
      LOG.error("Can not generate support bundle ", e);
      if (basePath != null) {
        supportBundleStatus.setStatus(CollectionState.FAILED);
        supportBundleStatus.setStatusDetails(e.getMessage());
        supportBundleStatus.setFinishTimestamp(System.currentTimeMillis());
        if (supportBundleJob != null) {
          addToStatus(basePath.getPath());
        }
      }
    }
    return uuid;
  }

  /**
   * Gets oldest folder from the root directory
   */
  private File getOldestFolder(File baseDirectory) {
    return Collections.min(DirUtils.listFiles(baseDirectory), (f1, f2) -> {
      SupportBundleStatus supportBundleStatusF1 = getSingleBundleJson(baseDirectory.getPath(), f1);
      SupportBundleStatus supportBundleStatusF2 = getSingleBundleJson(baseDirectory.getPath(), f2);
      CollectionState f1Status = supportBundleStatusF1.getStatus();
      CollectionState f2Status = supportBundleStatusF2.getStatus();
      if (f1Status.equals(CollectionState.FAILED) && !f2Status.equals(CollectionState.FAILED)) {
        return -1;
      } else if (!f1Status.equals(CollectionState.FAILED) && f2Status.equals(
          CollectionState.FAILED)) {
        return 1;
      }
      if (f1.lastModified() <= f2.lastModified()) {
        return -1;
      }
      return 1;
    });
  }

  /**
   * Deletes old folders after certain number of folders exist
   */
  private void deleteOldFolders(@Nullable File oldFilesDirectory) {
    try {
      DirUtils.deleteDirectoryContents(oldFilesDirectory);
    } catch (IOException e) {
      LOG.warn("Failed to clean up directory {}", oldFilesDirectory, e);
    }
  }

  public void close() {
    this.executorService.shutdown();
  }

  /**
   * Update status file
   */
  private void addToStatus(String basePath) {
    try (FileWriter statusFile = new FileWriter(new File(basePath, "status.json"))) {
      statusFile.write(GSON.toJson(supportBundleStatus));
      statusFile.flush();
    } catch (Exception e) {
      LOG.error("Can not update status file ", e);
    }
  }

  /**
   * Gets single support bundle status with uuid
   */
  public SupportBundleStatus getSingleBundleJson(String baseDirectory, File uuidFile) {
    SupportBundleStatus supportBundleStatus = new SupportBundleStatus();
    File singleSupportBundleFile = new File(baseDirectory, uuidFile.getName());
    File statusFile = new File(singleSupportBundleFile, "status.json");
    if (statusFile.exists()) {
      supportBundleStatus = readStatusJson(statusFile);
    }
    return supportBundleStatus;
  }

  private SupportBundleStatus readStatusJson(File statusFile) {
    SupportBundleStatus supportBundleStatus = new SupportBundleStatus();
    try (Reader reader = Files.newBufferedReader(statusFile.toPath(), StandardCharsets.UTF_8)) {
      supportBundleStatus = GSON.fromJson(reader, SupportBundleStatus.class);
    } catch (Exception e) {
      LOG.warn("Can not read status file ", e);
    }
    return supportBundleStatus;
  }
}
