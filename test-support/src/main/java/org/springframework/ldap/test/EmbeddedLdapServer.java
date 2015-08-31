/*
 * Copyright 2005-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ldap.test;

import org.apache.commons.io.FileUtils;
import org.apache.directory.api.ldap.model.constants.SchemaConstants;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.api.ldap.model.schema.LdapComparator;
import org.apache.directory.api.ldap.model.schema.SchemaManager;
import org.apache.directory.api.ldap.model.schema.comparators.NormalizingComparator;
import org.apache.directory.api.ldap.model.schema.registries.ComparatorRegistry;
import org.apache.directory.api.ldap.model.schema.registries.SchemaLoader;
import org.apache.directory.api.ldap.schema.extractor.SchemaLdifExtractor;
import org.apache.directory.api.ldap.schema.extractor.impl.DefaultSchemaLdifExtractor;
import org.apache.directory.api.ldap.schema.loader.LdifSchemaLoader;
import org.apache.directory.api.ldap.schema.manager.impl.DefaultSchemaManager;
import org.apache.directory.api.util.exception.Exceptions;
import org.apache.directory.server.constants.ServerDNConstants;
import org.apache.directory.server.core.DefaultDirectoryService;
import org.apache.directory.server.core.api.CacheService;
import org.apache.directory.server.core.api.DirectoryService;
import org.apache.directory.server.core.api.InstanceLayout;
import org.apache.directory.server.core.api.partition.Partition;
import org.apache.directory.server.core.api.schema.SchemaPartition;
import org.apache.directory.server.core.factory.JdbmPartitionFactory;
import org.apache.directory.server.core.factory.PartitionFactory;
import org.apache.directory.server.core.partition.ldif.LdifPartition;
import org.apache.directory.server.i18n.I18n;
import org.apache.directory.server.ldap.LdapServer;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Helper class for embedded Apache Directory Server.
 *
 * @author Mattias Hellborg Arthursson
 * @since 1.3.2
 */
public final class EmbeddedLdapServer {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedLdapServer.class);

    public static EmbeddedLdapServer newEmbeddedServer(String defaultPartitionName,
        String defaultPartitionSuffix, int port)
        throws Exception {

        String name = "apacheds-test1";

        PartitionFactory partitionFactory = createPartitionFactory();

        DirectoryService directoryService = createDirectoryService(name);

        buildInstanceDirectory(name, directoryService);

        buildCacheService(name, directoryService);

        initSchema(directoryService);

        initSystemPartition(directoryService, partitionFactory);

        Partition defaultPartition =
            createDefaultPartition(directoryService, partitionFactory, defaultPartitionName,
                defaultPartitionSuffix);

        directoryService.startup();

        addRootEntryIfRequired(directoryService, defaultPartition, defaultPartitionName,
            defaultPartitionSuffix);

        LdapServer ldapServer = new LdapServer();
        ldapServer.setDirectoryService(directoryService);

        TcpTransport ldapTransport = new TcpTransport(port);
        ldapServer.setTransports(ldapTransport);
        ldapServer.start();

        return new EmbeddedLdapServer(directoryService, ldapServer);
    }

    private static PartitionFactory createPartitionFactory()
        throws IllegalAccessException, InstantiationException, ClassNotFoundException {
        PartitionFactory partitionFactory;

        String typeName = System.getProperty("apacheds.partition.factory");

        if (typeName != null) {
            Class<? extends PartitionFactory> type =
                (Class<? extends PartitionFactory>) Class.forName(typeName);
            partitionFactory = type.newInstance();
        } else {
            partitionFactory = new JdbmPartitionFactory();
        }

        return partitionFactory;
    }

    private static DirectoryService createDirectoryService(String name) throws Exception {
        DirectoryService directoryService = new DefaultDirectoryService();

        directoryService.setShutdownHookEnabled(false);
        directoryService.setAllowAnonymousAccess(true);
        directoryService.setInstanceId(name);

        directoryService.getChangeLog().setEnabled(false);

        return directoryService;
    }

    private static void buildCacheService(String name, DirectoryService directoryService) {
        CacheService cacheService = new CacheService();
        cacheService.initialize(directoryService.getInstanceLayout(), name);
        directoryService.setCacheService(cacheService);
    }

    private static void buildInstanceDirectory(String name, DirectoryService directoryService)
        throws IOException {
        String instanceDirectoryPath = System.getProperty("instanceDirectory");

        if (instanceDirectoryPath == null) {
            instanceDirectoryPath = System.getProperty("java.io.tmpdir") + "/server-work-" + name;
        }

        InstanceLayout instanceLayout = new InstanceLayout(instanceDirectoryPath);

        if (instanceLayout.getInstanceDirectory().exists()) {
            try {
                FileUtils.deleteDirectory(instanceLayout.getInstanceDirectory());
            } catch (IOException e) {
                LOG.warn(
                    "couldn't delete the instance directory before initializing the DirectoryService",
                    e);
            }
        }

        directoryService.setInstanceLayout(instanceLayout);
    }

    private static void initSchema(DirectoryService directoryService) throws Exception {
        File workingDirectory = directoryService.getInstanceLayout().getPartitionsDirectory();

        // Extract the schema on disk (a brand new one) and load the registries
        File schemaRepository = new File(workingDirectory, "schema");
        SchemaLdifExtractor extractor = new DefaultSchemaLdifExtractor(workingDirectory);

        try {
            extractor.extractOrCopy();
        } catch (IOException ioe) {
            // The schema has already been extracted, bypass
        }

        SchemaLoader loader = new LdifSchemaLoader(schemaRepository);
        SchemaManager schemaManager = new DefaultSchemaManager(loader);

        // We have to load the schema now, otherwise we won't be able
        // to initialize the Partitions, as we won't be able to parse
        // and normalize their suffix Dn
        schemaManager.loadAllEnabled();

        // Tell all the normalizer comparators that they should not normalize anything
        ComparatorRegistry comparatorRegistry = schemaManager.getComparatorRegistry();

        for (LdapComparator<?> comparator : comparatorRegistry) {
            if (comparator instanceof NormalizingComparator) {
                ((NormalizingComparator) comparator).setOnServer();
            }
        }

        directoryService.setSchemaManager(schemaManager);

        // Init the LdifPartition
        LdifPartition ldifPartition = new LdifPartition(schemaManager, directoryService
            .getDnFactory());
        ldifPartition.setPartitionPath(new File(workingDirectory, "schema").toURI());
        SchemaPartition schemaPartition = new SchemaPartition(schemaManager);
        schemaPartition.setWrappedPartition(ldifPartition);
        directoryService.setSchemaPartition(schemaPartition);

        List<Throwable> errors = schemaManager.getErrors();

        if (errors.size() != 0) {
            throw new Exception(I18n.err(I18n.ERR_317, Exceptions.printErrors(errors)));
        }
    }

    private static void initSystemPartition(DirectoryService directoryService,
        PartitionFactory partitionFactory) throws Exception {
        // change the working directory to something that is unique
        // on the system and somewhere either under target directory
        // or somewhere in a temp area of the machine.

        // Inject the System Partition
        Partition systemPartition = partitionFactory.createPartition(directoryService
                .getSchemaManager(), directoryService.getDnFactory(), "system",
            ServerDNConstants.SYSTEM_DN, 500,
            new File(directoryService.getInstanceLayout().getPartitionsDirectory(), "system"));
        systemPartition.setSchemaManager(directoryService.getSchemaManager());

        partitionFactory.addIndex(systemPartition, SchemaConstants.OBJECT_CLASS_AT, 100);

        directoryService.setSystemPartition(systemPartition);
    }

    private static Partition createDefaultPartition(DirectoryService directoryService,
        PartitionFactory partitionFactory, String defaultPartitionName,
        String defaultPartitionSuffix) throws Exception {
        File workingDirectory = directoryService.getInstanceLayout().getPartitionsDirectory();

        Partition defaultPartition = partitionFactory.createPartition(
            directoryService.getSchemaManager(),
            directoryService.getDnFactory(),
            defaultPartitionName,
            defaultPartitionSuffix,
            1000,
            new File(workingDirectory, defaultPartitionName));

        partitionFactory.addIndex(defaultPartition, SchemaConstants.OBJECT_CLASS_AT, 200);

        directoryService.addPartition(defaultPartition);

        return defaultPartition;
    }

    private static void addRootEntryIfRequired(DirectoryService directoryService,
        Partition defaultPartition, String defaultPartitionName,
        String defaultPartitionSuffix) throws LdapException {

        // Inject the apache root entry if it does not already exist
        if (!directoryService.getAdminSession().exists(defaultPartition.getSuffixDn())) {
            Entry entry = directoryService.newEntry(new Dn(defaultPartitionSuffix));
            entry.add("objectClass", "top", "domain", "extensibleObject");
            entry.add("dc", defaultPartitionName);
            directoryService.getAdminSession().add(entry);
        }
    }

    private EmbeddedLdapServer(DirectoryService directoryService,
        LdapServer ldapServer) {
        this.directoryService = directoryService;
        this.ldapServer = ldapServer;
    }

    private final DirectoryService directoryService;
    private final LdapServer ldapServer;

    public DirectoryService getDirectoryService() {
        return directoryService;
    }

    public LdapServer getLdapServer() {
        return ldapServer;
    }

    public void shutdown() throws Exception {
        File workingDirectory = this.directoryService.getInstanceLayout().getInstanceDirectory();

        ldapServer.stop();
        directoryService.shutdown();

        FileUtils.deleteDirectory(workingDirectory);
    }
}
