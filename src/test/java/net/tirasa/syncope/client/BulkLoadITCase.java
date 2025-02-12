/*
 * Copyright (C) 2018 Tirasa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.tirasa.syncope.client;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.syncope.client.lib.SyncopeClient;
import org.apache.syncope.client.lib.SyncopeClientFactoryBean;
import org.apache.syncope.common.lib.Attr;
import org.apache.syncope.common.lib.SyncopeConstants;
import org.apache.syncope.common.lib.request.UserCR;
import org.apache.syncope.common.lib.to.ExecTO;
import org.apache.syncope.common.lib.to.ImplementationTO;
import org.apache.syncope.common.lib.to.MembershipTO;
import org.apache.syncope.common.lib.to.SchedTaskTO;
import org.apache.syncope.common.lib.to.TaskTO;
import org.apache.syncope.common.lib.types.IdRepoImplementationType;
import org.apache.syncope.common.lib.types.ImplementationEngine;
import org.apache.syncope.common.lib.types.TaskType;
import org.apache.syncope.common.rest.api.RESTHeaders;
import org.apache.syncope.common.rest.api.beans.ExecSpecs;
import org.apache.syncope.common.rest.api.service.ImplementationService;
import org.apache.syncope.common.rest.api.service.TaskService;
import org.apache.syncope.common.rest.api.service.UserService;
import org.apache.syncope.core.spring.security.SecureRandomUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BulkLoadITCase {

    private static final Logger LOG = LoggerFactory.getLogger(BulkLoadITCase.class);

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().findAndAddModules().build();

    private static final String ADDRESS = "http://localhost:9080/syncope/rest";

    private static final String ES_REINDEX = "org.apache.syncope.core.provisioning.java.job.ElasticsearchReindex";

    private static final SyncopeClientFactoryBean CLIENT_FACTORY = new SyncopeClientFactoryBean().setAddress(ADDRESS);

    private static final String ADMIN_UNAME = "admin";

    private static final String ADMIN_PWD = "password";

    private static SyncopeClient ADMIN_CLIENT;

    private static TaskService TASK_SERVICE;

    protected static ImplementationService IMPLEMENTATION_SERVICE;

    private static String ANONYMOUS_UNAME;

    private static String ANONYMOUS_KEY;

    private static boolean IS_EXT_SEARCH_ENABLED = false;

    private static final int THREADS = 10;

    private static Integer USERS;

    @BeforeAll
    public static void setup() throws IOException {
        Properties props = new Properties();
        try (InputStream propStream = BulkLoadITCase.class.getResourceAsStream("/bulkLoad.properties")) {
            props.load(propStream);

            USERS = Integer.valueOf(props.getProperty("bulk.load.users"));

            ANONYMOUS_UNAME = props.getProperty("security.anonymousUser");
            ANONYMOUS_KEY = props.getProperty("security.anonymousKey");
        } catch (IOException e) {
            fail(e.getMessage());
        }
        assertNotNull(USERS);

        ADMIN_CLIENT = CLIENT_FACTORY.create(ADMIN_UNAME, ADMIN_PWD);
        TASK_SERVICE = ADMIN_CLIENT.getService(TaskService.class);
        IMPLEMENTATION_SERVICE = ADMIN_CLIENT.getService(ImplementationService.class);

        JsonNode beans = JSON_MAPPER.readTree(
                (InputStream) WebClient.create(
                        StringUtils.substringBeforeLast(ADDRESS, "/") + "/actuator/beans",
                        ANONYMOUS_UNAME,
                        ANONYMOUS_KEY,
                        null).
                        accept(MediaType.APPLICATION_JSON).get().getEntity());

        JsonNode anySearchDAO = beans.findValues("anySearchDAO").get(0);
        IS_EXT_SEARCH_ENABLED = anySearchDAO.get("type").asText().contains("Elasticsearch");
    }

    private static ExecTO execTask(
            final TaskType type,
            final String taskKey,
            final String initialStatus,
            final int maxWaitSeconds,
            final boolean dryRun) {

        AtomicReference<TaskTO> taskTO = new AtomicReference<>(TASK_SERVICE.read(type, taskKey, true));
        int preSyncSize = taskTO.get().getExecutions().size();
        ExecTO execution = TASK_SERVICE.execute(new ExecSpecs.Builder().key(taskKey).dryRun(dryRun).build());
        Optional.ofNullable(initialStatus).ifPresent(status -> assertEquals(status, execution.getStatus()));
        assertNotNull(execution.getExecutor());

        await().atMost(maxWaitSeconds, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            try {
                taskTO.set(TASK_SERVICE.read(type, taskKey, true));
                return preSyncSize < taskTO.get().getExecutions().size();
            } catch (Exception e) {
                return false;
            }
        });

        return taskTO.get().getExecutions().get(taskTO.get().getExecutions().size() - 1);
    }

    @BeforeAll
    public static void elasticsearchInit() {
        if (IS_EXT_SEARCH_ENABLED) {
            ImplementationTO elasticSearchReindex = new ImplementationTO();
            elasticSearchReindex.setKey(ES_REINDEX);
            elasticSearchReindex.setEngine(ImplementationEngine.JAVA);
            elasticSearchReindex.setType(IdRepoImplementationType.TASKJOB_DELEGATE);
            elasticSearchReindex.setBody(ES_REINDEX);
            Response response = IMPLEMENTATION_SERVICE.create(elasticSearchReindex);
            elasticSearchReindex = IMPLEMENTATION_SERVICE.read(
                    elasticSearchReindex.getType(), response.getHeaderString(RESTHeaders.RESOURCE_KEY));
            assertNotNull(elasticSearchReindex);

            SchedTaskTO task = new SchedTaskTO();
            task.setActive(true);
            task.setName(ES_REINDEX);
            task.setJobDelegate(elasticSearchReindex.getKey());
            response = TASK_SERVICE.create(TaskType.SCHEDULED, task);

            execTask(
                    TaskType.SCHEDULED,
                    response.getHeaderString(RESTHeaders.RESOURCE_KEY),
                    null,
                    30,
                    false);
        }
    }

    private static UserCR build() {
        String username = SecureRandomUtils.generateRandomUUID().toString();
        String uniqueValue = String.valueOf(RandomUtils.secure().randomLong());

        UserCR user = new UserCR();
        user.setUsername(username);
        user.setPassword("password123");
        user.setRealm(SyncopeConstants.ROOT_REALM);

        user.getPlainAttrs().add(new Attr.Builder("email").value(username + "@syncope.apache.org").build());
        user.getPlainAttrs().add(new Attr.Builder("firstname").value("fn_" + username).build());
        user.getPlainAttrs().add(new Attr.Builder("surname").value("sn_" + username).build());
        user.getPlainAttrs().add(new Attr.Builder("address").
                value("Viale Vittoria Colonna, 97 - 65127 Pescara - Italy").
                value("Via Papa Giovanni XXIII, 8 - 66010 Miglianico (CH) - Italy").build());
        user.getPlainAttrs().add(new Attr.Builder("birth place").value("Colle Corvino").build());
        user.getPlainAttrs().add(new Attr.Builder("SSN").value(uniqueValue).build());
        user.getPlainAttrs().add(new Attr.Builder("gender").value("F").build());
        user.getPlainAttrs().add(new Attr.Builder("user type").
                value("Type " + RandomStringUtils.secure().next(
                        1, 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J')).build());
        user.getPlainAttrs().add(new Attr.Builder("mobile").value(uniqueValue).build());
        user.getPlainAttrs().add(new Attr.Builder("birthday").value("1977-09-08").build());

        user.getMemberships().add(new MembershipTO.Builder("e458f315-975e-4270-98f3-15975ea27057").build());

        MembershipTO membership = new MembershipTO.Builder("54b85719-91db-4917-b857-1991dbd91707").build();
        membership.getPlainAttrs().add(new Attr.Builder("recruitment").value("2017-05-11").build());
        membership.getPlainAttrs().add(new Attr.Builder("contract start").value("2017-05-11").build());
        membership.getPlainAttrs().add(new Attr.Builder("qualification").value("Qualification").build());
        membership.getPlainAttrs().add(new Attr.Builder("badge").value(uniqueValue).build());
        membership.getPlainAttrs().add(new Attr.Builder("institution").value("Institution").build());
        membership.getPlainAttrs().add(new Attr.Builder("organization").value("Organization").build());
        membership.getPlainAttrs().add(new Attr.Builder("contract level").value("Contract Level").build());
        membership.getPlainAttrs().add(new Attr.Builder("id number").value(uniqueValue).build());
        user.getMemberships().add(membership);

        return user;
    }

    @Test
    public void loadUsers() {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                UserService userService = ADMIN_CLIENT.getService(UserService.class);

                for (int j = 0; j < USERS / THREADS; j++) {
                    try {
                        userService.create(build());
                    } catch (Exception e) {
                        LOG.error("While creating user", e);
                    }
                }
            });
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(7, TimeUnit.DAYS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
