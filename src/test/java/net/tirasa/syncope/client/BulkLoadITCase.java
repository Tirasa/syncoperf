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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.syncope.client.lib.SyncopeClient;
import org.apache.syncope.client.lib.SyncopeClientFactoryBean;
import org.apache.syncope.common.lib.SyncopeConstants;
import org.apache.syncope.common.lib.to.AttrTO;
import org.apache.syncope.common.lib.to.MembershipTO;
import org.apache.syncope.common.lib.to.PagedResult;
import org.apache.syncope.common.lib.to.UserTO;
import org.apache.syncope.common.rest.api.beans.AnyQuery;
import org.apache.syncope.common.rest.api.service.UserService;
import org.apache.syncope.core.spring.security.SecureRandomUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class BulkLoadITCase {

    private static final SyncopeClientFactoryBean CLIENT_FACTORY =
            new SyncopeClientFactoryBean().setAddress("http://localhost:9080/syncope/rest/");

    private static final SyncopeClient CLIENT = CLIENT_FACTORY.create("admin", "password");

    private static Integer USERS;

    @BeforeAll
    public static void install() {
        Properties props = new Properties();
        try (InputStream propStream = BulkLoadITCase.class.getResourceAsStream("/bulkLoad.properties")) {
            props.load(propStream);

            USERS = Integer.valueOf(props.getProperty("bulk.load.users"));
        } catch (IOException e) {
            fail(e.getMessage());
        }
        assertNotNull(USERS);
    }

    private UserTO build() {
        String username = SecureRandomUtils.generateRandomUUID().toString().substring(0, 8);
        String uniqueValue = String.valueOf(System.currentTimeMillis());

        UserTO user = new UserTO();
        user.setUsername(username);
        user.setPassword("password123");
        user.setRealm(SyncopeConstants.ROOT_REALM);

        user.getPlainAttrs().add(new AttrTO.Builder().
                schema("email").value(username + "@syncope.apache.org").build());
        user.getPlainAttrs().add(new AttrTO.Builder().
                schema("firstname").value("fn_" + username).build());
        user.getPlainAttrs().add(new AttrTO.Builder().
                schema("surname").value("sn_" + username).build());
        user.getPlainAttrs().add(new AttrTO.Builder().
                schema("address").
                value("Viale G. D'Annunzio, 267 - 65127 Pescara - Italy").
                value("Via Papa Giovanni XXIII, 8 - 66010 Miglianico (CH) - Italy").build());
        user.getPlainAttrs().add(new AttrTO.Builder().
                schema("birth place").value("Colle Corvino").build());
        user.getPlainAttrs().add(new AttrTO.Builder().
                schema("SSN").value(uniqueValue).build());
        user.getPlainAttrs().add(new AttrTO.Builder().
                schema("gender").value("F").build());
        user.getPlainAttrs().add(new AttrTO.Builder().
                schema("user type").
                value("Type " + RandomStringUtils.random(1, 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J')).build());
        user.getPlainAttrs().add(new AttrTO.Builder().
                schema("mobile").value(uniqueValue).build());
        user.getPlainAttrs().add(new AttrTO.Builder().
                schema("birthday").value("1977-09-08").build());

        user.getMemberships().add(new MembershipTO.Builder().group("e458f315-975e-4270-98f3-15975ea27057").build());

        MembershipTO membership = new MembershipTO.Builder().group("54b85719-91db-4917-b857-1991dbd91707").build();
        membership.getPlainAttrs().add(new AttrTO.Builder().schema("recruitment").value("2017-05-11").build());
        membership.getPlainAttrs().add(new AttrTO.Builder().schema("contract start").value("2017-05-11").build());
        membership.getPlainAttrs().add(new AttrTO.Builder().schema("qualification").value("Qualification").build());
        membership.getPlainAttrs().add(new AttrTO.Builder().schema("badge").value(uniqueValue).build());
        membership.getPlainAttrs().add(new AttrTO.Builder().schema("institution").value("Institution").build());
        membership.getPlainAttrs().add(new AttrTO.Builder().schema("organization").value("Organization").build());
        membership.getPlainAttrs().add(new AttrTO.Builder().schema("contract level").value("Contract Level").build());
        membership.getPlainAttrs().add(new AttrTO.Builder().schema("id number").value(uniqueValue).build());
        user.getMemberships().add(membership);

        return user;
    }

    @Test
    public void loadUsers() {
        UserService userService = CLIENT.getService(UserService.class);

        for (int i = 0; i < USERS; i++) {
            userService.create(build(), true);
        }

        PagedResult<UserTO> matching = userService.search(new AnyQuery.Builder().realm(SyncopeConstants.ROOT_REALM).
                fiql(SyncopeClient.getUserSearchConditionBuilder().is("username").notNullValue().query()).
                page(1).size(0).build());
        assertEquals(USERS.intValue(), matching.getTotalCount());
    }
}
