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

package io.cdap.cdap.app.runtime.spark.distributed.k8s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Module;
import io.cdap.cdap.api.app.ApplicationSpecification;
import io.cdap.cdap.api.artifact.ArtifactId;
import io.cdap.cdap.api.artifact.ArtifactScope;
import io.cdap.cdap.api.plugin.Plugin;
import io.cdap.cdap.app.runtime.Arguments;
import io.cdap.cdap.app.runtime.ProgramOptions;
import io.cdap.cdap.app.runtime.spark.distributed.SparkContainerLauncher;
import io.cdap.cdap.common.conf.CConfiguration;
import io.cdap.cdap.common.guice.ConfigModule;
import io.cdap.cdap.common.guice.IOModule;
import io.cdap.cdap.common.guice.SupplierProviderBridge;
import io.cdap.cdap.common.internal.remote.InternalAuthenticator;
import io.cdap.cdap.common.internal.remote.RemoteClientFactory;
import io.cdap.cdap.common.lang.jar.BundleJarUtil;
import io.cdap.cdap.internal.app.ApplicationSpecificationAdapter;
import io.cdap.cdap.internal.app.runtime.codec.ArgumentsCodec;
import io.cdap.cdap.internal.app.runtime.codec.ProgramOptionsCodec;
import io.cdap.cdap.internal.app.spark.ArtifactFetcherService;
import io.cdap.cdap.internal.app.worker.sidecar.ArtifactLocalizer;
import io.cdap.cdap.logging.guice.RemoteLogAppenderModule;
import io.cdap.cdap.master.environment.MasterEnvironments;
import io.cdap.cdap.master.spi.environment.MasterEnvironment;
import io.cdap.cdap.master.spi.environment.MasterEnvironmentContext;
import io.cdap.cdap.proto.id.ProgramId;
import io.cdap.cdap.security.auth.context.AuthenticationContextModules;
import io.cdap.cdap.security.guice.CoreSecurityModule;
import io.cdap.cdap.security.guice.CoreSecurityRuntimeModule;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.twill.discovery.DiscoveryService;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.apache.twill.filesystem.LocalLocationFactory;
import org.apache.twill.filesystem.Location;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Spark container launcher for launching spark drivers, and also allowing spark executors to fetch artifacts from it.
 */
public class SparkContainerDriverLauncher {
  private static final Gson GSON = ApplicationSpecificationAdapter.addTypeAdapters(new GsonBuilder())
    .registerTypeAdapter(Arguments.class, new ArgumentsCodec())
    .registerTypeAdapter(ProgramOptions.class, new ProgramOptionsCodec())
    .create();
  private static final String PROGRAM_JAR_NAME = "program.jar";
  private static final String PROGRAM_JAR_EXPANDED_NAME = "program.jar.expanded.zip";
  private static final String CDAP_APP_SPEC_KEY = "cdap.spark.app.spec";
  private static final String PROGRAM_ID_KEY = "cdap.spark.program.id";
  private static final String DEFAULT_DELEGATE_CLASS = "org.apache.spark.deploy.SparkSubmit";
  private static final String DELEGATE_CLASS_FLAG = "--delegate-class";

  private static final String WORKING_DIRECTORY = "/opt/spark/work-dir/";
  private static final String SPARK_LOCAL_DIR = System.getenv("SPARK_LOCAL_DIRS") + "/";
  private static final String CONFIGMAP_FILES_BASE_PATH = "/etc/cdap/localizefiles/";
  private static final String CCONF_PATH = WORKING_DIRECTORY + "cConf.xml";
  private static final String HCONF_PATH = WORKING_DIRECTORY + "hConf.xml";

  private static ArtifactFetcherService artifactFetcherService;

  public static void main(String[] args) throws Exception {
    String delegateClass = DEFAULT_DELEGATE_CLASS;
    List<String> delegateArgs = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals(DELEGATE_CLASS_FLAG)) {
        delegateClass = args[i + 1];
        i++;
        continue;
      }
      delegateArgs.add(args[i]);
    }

    // Copy all the files from config map
    for (File compressedFile : new File(CONFIGMAP_FILES_BASE_PATH).listFiles()) {
      if (compressedFile.isFile()) {
        decompress(compressedFile.getAbsolutePath(), WORKING_DIRECTORY + compressedFile.getName());
      }
    }
    // TODO: CDAP-18525: remove below line once this jira is fixed
    FileUtils.copyDirectory(new File(WORKING_DIRECTORY), new File(SPARK_LOCAL_DIR));

    CConfiguration cConf = CConfiguration.create(new File(CCONF_PATH));
    Configuration hConf = new Configuration();
    hConf.addResource(new org.apache.hadoop.fs.Path("file:" + new File(HCONF_PATH).getAbsolutePath()));
    ArtifactLocalizerClient fetchArtifacts = createArtifactLocalizerClient(cConf, hConf);

    ApplicationSpecification spec =
      GSON.fromJson(hConf.getRaw(CDAP_APP_SPEC_KEY), ApplicationSpecification.class);
    ProgramId programId = GSON.fromJson(hConf.getRaw(PROGRAM_ID_KEY), ProgramId.class);

    //Create plugin location for storing plugin jars
    Path pluginsLocation = new File(WORKING_DIRECTORY).getAbsoluteFile().toPath().resolve("artifacts_archive.jar")
      .toAbsolutePath();
    Files.createDirectories(pluginsLocation);

    // Fetching plugin artifacts from app-fabric
    for (Plugin plugin : spec.getPlugins().values()) {
      File tempLocation = fetchArtifacts.localizeArtifact(plugin.getArtifactId(), programId.getNamespace());
      String pluginName = String.format("%s-%s-%s.jar",
                                        plugin.getArtifactId().getScope().toString(),
                                        plugin.getArtifactId().getName(),
                                        plugin.getArtifactId().getVersion().toString());
      BundleJarUtil.unJar(tempLocation, pluginsLocation.resolve(pluginName).toFile());
    }

    // Fetching program.jar from app-fabric and expand it
    Path programJarLocation = new File(WORKING_DIRECTORY).getAbsoluteFile().toPath();
    File tempLocation = fetchArtifacts.localizeArtifact(spec.getArtifactId(), programId.getNamespace());
    BundleJarUtil.unJar(tempLocation, programJarLocation.resolve(PROGRAM_JAR_EXPANDED_NAME).toFile());
    Files.copy(tempLocation.toPath(), programJarLocation.resolve(PROGRAM_JAR_NAME));

    artifactFetcherService =
      new ArtifactFetcherService(cConf, createBundle(new File(WORKING_DIRECTORY).getAbsoluteFile().toPath()));
    artifactFetcherService.startAndWait();

    SparkContainerLauncher.launch(delegateClass, delegateArgs.toArray(new String[delegateArgs.size()]), false, "k8s");
  }


  private static ArtifactLocalizerClient createArtifactLocalizerClient(CConfiguration cConf, Configuration hConf)
    throws Exception {
    MasterEnvironment masterEnv = MasterEnvironments.create(cConf, "k8s");
    if (masterEnv == null) {
      throw new RuntimeException("Unable to initialize k8s masterEnv from cConf.");
    }
    MasterEnvironmentContext context = MasterEnvironments.createContext(cConf, hConf, masterEnv.getName());
    masterEnv.initialize(context);
    MasterEnvironments.setMasterEnvironment(masterEnv);

    Injector injector = createInjector(cConf, hConf, masterEnv);
    return injector.getInstance(ArtifactLocalizerClient.class);
  }

  private static Injector createInjector(CConfiguration cConf, Configuration hConf, MasterEnvironment masterEnv) {
    List<Module> modules = new ArrayList<>();

    CoreSecurityModule coreSecurityModule = CoreSecurityRuntimeModule.getDistributedModule(cConf);

    modules.add(new ConfigModule(cConf, hConf));
    modules.add(new IOModule());
    modules.add(new AuthenticationContextModules().getMasterWorkerModule());
    modules.add(coreSecurityModule);

    modules.add(new AbstractModule() {
      @Override
      protected void configure() {
        bind(DiscoveryService.class)
          .toProvider(new SupplierProviderBridge<>(masterEnv.getDiscoveryServiceSupplier()));
        bind(DiscoveryServiceClient.class)
          .toProvider(new SupplierProviderBridge<>(masterEnv.getDiscoveryServiceClientSupplier()));
      }
    });
    modules.add(new RemoteLogAppenderModule());

    return Guice.createInjector(modules);
  }

  private static Location createBundle(Path workingDirectory) throws IOException {
    String bundleName = String.format("%s-%s.jar", "bundle", System.currentTimeMillis());
    File bundleFile = com.google.common.io.Files.createTempDir().toPath().resolve(bundleName).toFile();
    BundleJarUtil.createJar(workingDirectory.toFile(), bundleFile);
    return new LocalLocationFactory().create(bundleFile.getPath());
  }

  private static class ArtifactLocalizerClient {

    private final ArtifactLocalizer artifactLocalizer;

    @Inject
    ArtifactLocalizerClient(DiscoveryServiceClient discoveryServiceClient,
                            InternalAuthenticator internalAuthenticator,
                            CConfiguration cConf) {

      RemoteClientFactory remoteClientFactory =
        new RemoteClientFactory(discoveryServiceClient, internalAuthenticator);
      this.artifactLocalizer = new ArtifactLocalizer(cConf, remoteClientFactory);
    }

    File localizeArtifact(ArtifactId artifactId, String programNamespace) throws Exception {
      String namespace = artifactId.getScope().name().equalsIgnoreCase(ArtifactScope.USER.toString()) ?
        programNamespace : artifactId.getScope().name();
      io.cdap.cdap.proto.id.ArtifactId aId =
        new io.cdap.cdap.proto.id.ArtifactId(namespace,
                                             artifactId.getName(),
                                             artifactId.getVersion().getVersion());
      return artifactLocalizer.fetchArtifact(aId);
    }
  }

  private static void decompress(String gzipFile, String newFile) throws IOException {
    byte[] buffer = new byte[1024 * 500]; // use 500kb buffer
    try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(gzipFile));
         FileOutputStream fos = new FileOutputStream(newFile)) {
      int length;
      while ((length = gis.read(buffer)) > 0) {
        fos.write(buffer, 0, length);
      }
    }
  }
}