/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.ctrl.artifacts;

import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ArtifactType;
import io.reshapr.ctrl.model.Operation;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;
import io.reshapr.util.ReferenceResolver;

import com.github.os72.protocjar.Protoc;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * An implementation of ArtifactImporter that deals with Protobuf v3 specification documents.
 *  * @author laurent
 */
public class ProtobufImporter implements ArtifactImporter {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final String BINARY_DESCRIPTOR_EXT = ".pbb";
   private static final String BUILTIN_LIBRARY_PREFIX = "google/protobuf";

   private final PackageServices packageServices;
   private final String protoDirectory;
   private final String protoFileName;
   private final ReferenceResolver referenceResolver;
   private String specContent;
   private DescriptorProtos.FileDescriptorSet fds;

   protected String serviceName;
   protected String serviceVersion;

   /**
    * Build a new importer.
    * @param protoFilePath     The path to local proto spec file
    * @param referenceResolver An optional resolver for references present into the Protobuf file
    * @throws IOException if project file cannot be found or read.
    */
   public ProtobufImporter(String protoFilePath, ReferenceResolver referenceResolver) throws IOException {
      this.referenceResolver = referenceResolver;

      // Move proto file to a unique subdir that matches its package name (to avoid conflicts)
      // This will allow protoc to find the relative imports we will download later on.
      Path protoPath = Paths.get(protoFilePath).toAbsolutePath().normalize();
      Path parentDir = protoPath.getParent();
      if (parentDir == null) {
         throw new IOException("Protobuf schema file has no parent directory: " + protoFilePath);
      }
      packageServices = getPackage(protoPath);
      if (packageServices.packageName != null) {
         String packagePath = packageServices.packageName.replace(".", "/");

         String uuid = UUID.randomUUID().toString();
         Path newProtoPath = parentDir.resolve(uuid + "/" + packagePath + "/" + protoPath.getFileName());

         try {
            Files.createDirectories(newProtoPath.getParent());
            Files.createFile(newProtoPath);
         } catch (FileAlreadyExistsException faee) {
            // Ignore if the file already exists, it means we have already downloaded it.
         }
         Files.copy(protoPath, newProtoPath, StandardCopyOption.REPLACE_EXISTING);

         // Prepare file, path and name for easier process.
         protoDirectory = parentDir.resolve(uuid).toFile().getAbsolutePath();
         protoFileName = packagePath + "/" + protoPath.getFileName();

         // Now switch the proto and file paths.
         protoFilePath = newProtoPath.toString();
         protoPath = newProtoPath;
      } else {
         // Prepare file, path and name for easier process.
         protoDirectory = parentDir.toFile().getAbsolutePath();
         protoFileName = protoPath.getFileName().toString();
      }

      // Prepare protoc arguments.
      String[] args = { "-v3.21.8", "--include_std_types", "--include_imports", "--proto_path=" + protoDirectory,
            "--descriptor_set_out=" + protoDirectory + "/" + protoFileName + BINARY_DESCRIPTOR_EXT, protoFileName };

      // Track resolved imports (must be cleanup after success of failure).
      List<File> resolvedImportsLocalFiles = null;
      try {
         // Read spec bytes.
         byte[] bytes = Files.readAllBytes(protoPath);
         specContent = new String(bytes, StandardCharsets.UTF_8);

         // Resolve and retrieve imports if any.
         if (referenceResolver != null) {
            String rootBaseUrl = referenceResolver.getBaseRepositoryUrl();
            resolvedImportsLocalFiles = new ArrayList<>();
            resolveAndPrepareRemoteImports(protoPath, resolvedImportsLocalFiles, rootBaseUrl);
         }

         // Run Protoc.
         int result = Protoc.runProtoc(args);

         File protoFileB = new File(protoDirectory, protoFileName + BINARY_DESCRIPTOR_EXT);
         fds = DescriptorProtos.FileDescriptorSet.parseFrom(new FileInputStream(protoFileB));
      } catch (InterruptedException ie) {
         logger.errorf("Protobuf schema compilation has been interrupted on %s", protoFilePath);
         Thread.currentThread().interrupt();
      } catch (Exception e) {
         throw new IOException("Protobuf schema file parsing error on " + protoFilePath + ": " + e.getMessage());
      } finally {
         // Cleanup locally downloaded dependencies needed by protoc.
         if (resolvedImportsLocalFiles != null) {
            resolvedImportsLocalFiles.forEach(File::delete);
         }
      }
   }

   @Override
   public void setServiceName(String serviceName) {
      this.serviceName = serviceName;
   }

   @Override
   public void setServiceVersion(String serviceVersion) {
      this.serviceVersion = serviceVersion;
   }

   @Override
   public List<Service> getServiceDefinitions() throws ArtifactImportException {
      List<Service> results = new ArrayList<>();

      // Prepare dependencies.
      List<Descriptors.FileDescriptor> dependencies = new ArrayList<>();
      for (DescriptorProtos.FileDescriptorProto fdp : fds.getFileList()) {
         // Retrieve version from package name.
         // org.acme package => org.acme version
         // org.acme.v1 package => v1 version
         String packageName = fdp.getPackage();
         String[] parts = packageName.split("\\.");
         String version = (parts.length > 2 ? parts[parts.length - 1] : packageName);

         Descriptors.FileDescriptor fd = null;
         try {
            fd = Descriptors.FileDescriptor.buildFrom(fdp,
                  dependencies.toArray(new Descriptors.FileDescriptor[dependencies.size()]), true);
            dependencies.add(fd);
         } catch (Descriptors.DescriptorValidationException e) {
            throw new ArtifactImportException(
                  "Exception while building Protobuf descriptor, probably a missing dependency issue: "
                        + e.getMessage());
         }

         for (Descriptors.ServiceDescriptor sd : fd.getServices()) {
            if (packageServices.packageName != null &&
                  (!packageServices.packageName.equals(fd.getPackage()))
                  || !packageServices.services.contains(sd.getName())) {
               // If the service is not in the package and list of services, skip it.
               continue;
            }
            // Build a new service.
            Service service = new Service();

            service.name = serviceName != null ? serviceName : sd.getFullName();
            service.version = serviceVersion != null ? serviceVersion : version;
            service.type = ServiceType.GRPC;

            // Then build its operations.
            service.operations = extractOperations(sd);

            results.add(service);
         }
      }
      return results;
   }

   @Override
   public List<Artifact> getArtifactDefinitions(Service service) throws ArtifactImportException {
      List<Artifact> results = new ArrayList<>();

      // Build 2 artifacts: one with plain text, another with base64 encoded binary descriptor.
      Artifact textArtifact = new Artifact();
      textArtifact.name = service.name + "-" + service.version + ".proto";
      textArtifact.type = ArtifactType.PROTOBUF_SCHEMA;
      textArtifact.content = specContent;
      results.add(textArtifact);

      try {
         byte[] binaryPB = Files.readAllBytes(Path.of(protoDirectory, protoFileName + BINARY_DESCRIPTOR_EXT));
         String base64PB = new String(Base64.getEncoder().encode(binaryPB), StandardCharsets.UTF_8);

         Artifact descArtifact = new Artifact();
         descArtifact.name = service.name + "-" + service.version + BINARY_DESCRIPTOR_EXT;
         descArtifact.type = ArtifactType.PROTOBUF_DESCRIPTOR;
         descArtifact.content = base64PB;
         results.add(descArtifact);
      } catch (Exception e) {
         logger.error("Exception while encoding Protobuf binary descriptor into base64", e);
         throw new ArtifactImportException("Exception while encoding Protobuf binary descriptor into base64");
      }

      // Now build artifacts for dependencies if any.
      if (referenceResolver != null) {
         referenceResolver.getRelativeResolvedReferences().forEach((p, f) -> {
            Artifact protoArtifact = new Artifact();
            protoArtifact.name = service.name + "-" + service.version + "-" + p.replace("/", "~1");
            protoArtifact.type = ArtifactType.PROTOBUF_SCHEMA;
            protoArtifact.path = p;
            try {
               protoArtifact.content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            } catch (IOException ioe) {
               logger.warn("Exception while setting content of '%s' Protobuf resource", protoArtifact.name, ioe);
               logger.warn("Pursuing on next resource as it was for information purpose only");
            }
            results.add(protoArtifact);
         });
         referenceResolver.cleanResolvedReferences();
      }
      return results;
   }

   /** Initial proto file package and embedded services. */
   private record PackageServices(String packageName, List<String> services) {
   }

   /** Extract the package name from a proto file. */
   private PackageServices getPackage(Path protoFilePath) {
      String packageName = null;
      List<String> servicesNames = new ArrayList<>();

      String line = null;
      try (BufferedReader reader = Files.newBufferedReader(protoFilePath, StandardCharsets.UTF_8)) {
         while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("package ")) {
               // Extract package name.
               String protoPackage = line.substring("package ".length()).trim();
               if (protoPackage.endsWith(";")) {
                  protoPackage = protoPackage.substring(0, protoPackage.length() - 1);
               }
               packageName = protoPackage;
            } else if (line.startsWith("service ")) {
               // Extract service name.
               String serviceName = line.substring("service ".length()).trim();
               if (serviceName.endsWith("{")) {
                  serviceName = serviceName.substring(0, serviceName.length() - 1).trim();
               }
               servicesNames.add(serviceName);
            }
         }
      } catch (Exception e) {
         logger.error("Exception while retrieving protobuf package", e);
      }
      return new PackageServices(packageName, servicesNames);
   }

   /** Analyse a protofile imports, resolve and retrieve them from remote to allow protoc to run later. */
   private void resolveAndPrepareRemoteImports(Path protoFilePath, List<File> resolvedImportsLocalFiles,
                                               String rootBaseUrl) throws IOException {
      String line = null;
      String protoPackage = null;
      try (BufferedReader reader = Files.newBufferedReader(protoFilePath, StandardCharsets.UTF_8)) {
         while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("package ")) {
               // Extract package name.
               protoPackage = line.substring("package ".length()).trim();
               if (protoPackage.endsWith(";")) {
                  protoPackage = protoPackage.substring(0, protoPackage.length() - 1);
               }
               logger.debugf("Found a package in protobuf: '%s'", protoPackage);

            } else if (line.startsWith("import ")) {
               // Deal with import statement.
               String importStr = line.substring("import ".length() + 1);
               // Remove semicolon and quotes/double-quotes.
               if (importStr.endsWith(";")) {
                  importStr = importStr.substring(0, importStr.length() - 1);
               }
               if (importStr.endsWith("\"") || importStr.endsWith("'")) {
                  importStr = importStr.substring(0, importStr.length() - 1);
               }
               if (importStr.startsWith("\"") || importStr.startsWith("'")) {
                  importStr = importStr.substring(1);
               }
               logger.debugf("Found an import to resolve in protobuf: '%s'", importStr);

               // Check that this lib is not in built-in ones.
               if (!importStr.startsWith(BUILTIN_LIBRARY_PREFIX)) {
                  Path importPath = null;
                  if (protoPackage != null) {
                     int levelsToRoot = protoPackage.split("\\.").length;
                     String relativeImportStr = "../".repeat(levelsToRoot) + importStr;
                     importPath = protoFilePath.getParent().resolve(relativeImportStr);
                     downloadImportReferenceAndProgress(importPath, relativeImportStr, resolvedImportsLocalFiles, rootBaseUrl);
                  } else {
                     // No package, so just use the import string as is.
                     importPath = protoFilePath.getParent().resolve(importStr);
                     downloadImportReferenceAndProgress(importPath, importStr, resolvedImportsLocalFiles, rootBaseUrl);
                  }
               }
            }
         }
      } catch (Exception e) {
         logger.error("Exception while retrieving protobuf dependency", e);
      }
   }

   /** Download a remote import reference and write it to local file system. Progressively resolve its own imports. */
   private void downloadImportReferenceAndProgress(Path importPath, String importStr,
                                                   List<File> resolvedImportsLocalFiles, String rootBaseUrl) throws FileNotFoundException, IOException {
      referenceResolver.setBaseRepositoryUrl(rootBaseUrl);
      String importContent = referenceResolver.getReferenceContent(importStr, StandardCharsets.UTF_8);
      if (!Files.exists(importPath)) {
         try {
            Files.createDirectories(importPath.getParent());
            Files.createFile(importPath);
         } catch (FileAlreadyExistsException faee) {
            logger.warnf("Exception while writing protobuf dependency: %s", importPath.toFile().getAbsolutePath());
         }
      }
      logger.infof("Writing protobuf import %s to %s", importStr, importPath);
      Files.write(importPath, importContent.getBytes(StandardCharsets.UTF_8));
      resolvedImportsLocalFiles.add(importPath.toFile());

      // Now go down the resource content and resolve its own imports.
      if (importStr.startsWith("../")) {
         rootBaseUrl = referenceResolver.getReferenceURL(importStr);
      }
      resolveAndPrepareRemoteImports(importPath, resolvedImportsLocalFiles, rootBaseUrl);
   }

   /** Extract the operations from GRPC service methods. */
   private List<Operation> extractOperations(Descriptors.ServiceDescriptor service) {
      List<Operation> results = new ArrayList<>();

      for (Descriptors.MethodDescriptor method : service.getMethods()) {
         Operation operation = new Operation();
         operation.name = method.getName();
         if (method.getInputType() != null) {
            operation.inputName = "." + method.getInputType().getFullName();
         }
         if (method.getOutputType() != null) {
            operation.outputName = "." + method.getOutputType().getFullName();
         }

         results.add(operation);
      }
      return results;
   }
}
