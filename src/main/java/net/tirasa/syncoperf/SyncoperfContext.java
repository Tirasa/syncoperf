/**
 * Copyright (C) 2025 Tirasa
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
package net.tirasa.syncoperf;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.http.HttpHost;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.syncope.common.lib.types.AuditLoggerName;
import org.apache.syncope.core.logic.audit.AuditAppender;
import org.apache.syncope.core.logic.audit.ElasticsearchAuditAppender;
import org.apache.syncope.core.persistence.api.DomainHolder;
import org.apache.syncope.core.persistence.api.attrvalue.validation.PlainAttrValidationManager;
import org.apache.syncope.core.persistence.api.dao.AnyObjectDAO;
import org.apache.syncope.core.persistence.api.dao.AnySearchDAO;
import org.apache.syncope.core.persistence.api.dao.AuditConfDAO;
import org.apache.syncope.core.persistence.api.dao.DynRealmDAO;
import org.apache.syncope.core.persistence.api.dao.GroupDAO;
import org.apache.syncope.core.persistence.api.dao.PlainSchemaDAO;
import org.apache.syncope.core.persistence.api.dao.RealmDAO;
import org.apache.syncope.core.persistence.api.dao.RoleDAO;
import org.apache.syncope.core.persistence.api.dao.UserDAO;
import org.apache.syncope.core.persistence.api.entity.AnyUtilsFactory;
import org.apache.syncope.core.persistence.api.entity.EntityFactory;
import org.apache.syncope.core.persistence.jpa.dao.ElasticsearchAnySearchDAO;
import org.apache.syncope.core.persistence.jpa.dao.ElasticsearchAuditConfDAO;
import org.apache.syncope.core.persistence.jpa.dao.ElasticsearchRealmDAO;
import org.apache.syncope.core.persistence.jpa.dao.JPARealmDAO;
import org.apache.syncope.core.persistence.jpa.dao.PGJPAJSONAnySearchDAO;
import org.apache.syncope.core.persistence.jpa.dao.PGJPAJSONAuditConfDAO;
import org.apache.syncope.ext.elasticsearch.client.ElasticsearchClientFactoryBean;
import org.apache.syncope.ext.elasticsearch.client.ElasticsearchIndexLoader;
import org.apache.syncope.ext.elasticsearch.client.ElasticsearchIndexManager;
import org.apache.syncope.ext.elasticsearch.client.ElasticsearchProperties;
import org.apache.syncope.ext.elasticsearch.client.ElasticsearchUtils;
import org.apache.syncope.ext.elasticsearch.client.SyncopeElasticsearchHealthContributor;
import org.identityconnectors.common.CollectionUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class SyncoperfContext {

    @Value("${elasticsearch.disable:true}")
    private boolean elasitcSearchDisable;

    @Primary
    @Bean
    public ElasticsearchClientFactoryBean elasticsearchClientFactoryBean(final ElasticsearchProperties props) {
        return new ElasticsearchClientFactoryBean(
                CollectionUtil.nullAsEmpty(props.getHosts()).stream().
                        map(HttpHost::create).collect(Collectors.toList()));
    }

    @Primary
    @Bean
    public ElasticsearchIndexManager elasticsearchIndexManager(
            final ElasticsearchProperties props,
            final ElasticsearchClientFactoryBean clientFactory,
            final ElasticsearchUtils elasticsearchUtils) throws Exception {

        if (elasitcSearchDisable) {
            return null;
        }

        return new ElasticsearchIndexManager(
                clientFactory.getObject(),
                elasticsearchUtils,
                props.getNumberOfShards(),
                props.getNumberOfReplicas());
    }

    @Primary
    @Bean
    public ElasticsearchIndexLoader elasticsearchIndexLoader(final ElasticsearchIndexManager indexManager) {
        if (elasitcSearchDisable) {
            return null;
        }

        return new ElasticsearchIndexLoader(indexManager);
    }

    @Primary
    @Bean(name = { "anySearchDAO", "pgJPAJSONAnySearchDAO", "elasticsearchAnySearchDAO" })
    public AnySearchDAO anySearchDAO(
            final ElasticsearchProperties props,
            final RealmDAO realmDAO,
            final @Lazy DynRealmDAO dynRealmDAO,
            final @Lazy UserDAO userDAO,
            final @Lazy GroupDAO groupDAO,
            final @Lazy AnyObjectDAO anyObjectDAO,
            final PlainSchemaDAO schemaDAO,
            final EntityFactory entityFactory,
            final AnyUtilsFactory anyUtilsFactory,
            final PlainAttrValidationManager validator,
            final ElasticsearchClientFactoryBean clientFactory) throws Exception {

        if (elasitcSearchDisable) {
            return new PGJPAJSONAnySearchDAO(
                    realmDAO,
                    dynRealmDAO,
                    userDAO,
                    groupDAO,
                    anyObjectDAO,
                    schemaDAO,
                    entityFactory,
                    anyUtilsFactory,
                    validator);
        }

        return new ElasticsearchAnySearchDAO(
                realmDAO,
                dynRealmDAO,
                userDAO,
                groupDAO,
                anyObjectDAO,
                schemaDAO,
                entityFactory,
                anyUtilsFactory,
                validator,
                clientFactory.getObject(),
                props.getIndexMaxResultWindow());
    }

    @Primary
    @Bean(name = { "realmDAO", "elasticsearchRealmDAO" })
    public RealmDAO realmDAO(
            final @Lazy RoleDAO roleDAO,
            final ApplicationEventPublisher publisher,
            final ElasticsearchProperties props,
            final ElasticsearchClientFactoryBean clientFactory) throws Exception {

        if (elasitcSearchDisable) {
            return new JPARealmDAO(roleDAO, publisher);
        }

        return new ElasticsearchRealmDAO(
                roleDAO, publisher, clientFactory.getObject(), props.getIndexMaxResultWindow());
    }

    @Primary
    @Bean(name = { "auditConfDAO", "pgJPAJSONAuditConfDAO", "elasticsearchAuditConfDAO" })
    public AuditConfDAO auditConfDAO(
            final ElasticsearchProperties props,
            final ElasticsearchClientFactoryBean clientFactory) throws Exception {

        if (elasitcSearchDisable) {
            return new PGJPAJSONAuditConfDAO();
        }

        return new ElasticsearchAuditConfDAO(clientFactory.getObject(), props.getIndexMaxResultWindow());
    }

    @Primary
    @Bean(name = { "defaultAuditAppenders", "elasticsearchDefaultAuditAppenders" })
    public List<AuditAppender> defaultAuditAppenders(
            final DomainHolder domainHolder,
            final ElasticsearchIndexManager elasticsearchIndexManager) {

        List<AuditAppender> auditAppenders = new ArrayList<>();

        if (!elasitcSearchDisable) {
            LoggerContext logCtx = (LoggerContext) LogManager.getContext(false);
            domainHolder.getDomains().forEach((domain, dataSource) -> {
                AuditAppender appender = new ElasticsearchAuditAppender(domain, elasticsearchIndexManager);

                LoggerConfig logConf = new LoggerConfig(AuditLoggerName.getAuditLoggerName(domain), null, false);
                logConf.addAppender(appender.getTargetAppender(), Level.DEBUG, null);
                logConf.setLevel(Level.DEBUG);
                logCtx.getConfiguration().addLogger(logConf.getName(), logConf);

                auditAppenders.add(appender);
            });
        }

        return auditAppenders;
    }

    @Primary
    @Bean(name = {
        "syncopeElasticsearchHealthContributor", "elasticsearchHealthIndicator", "elasticsearchHealthContributor" })
    public HealthContributor syncopeElasticsearchHealthContributor(final ElasticsearchClientFactoryBean clientFactory)
            throws Exception {

        if (elasitcSearchDisable) {
            return null;
        }

        return new SyncopeElasticsearchHealthContributor(clientFactory.getObject());
    }
}
