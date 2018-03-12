/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.haulmont.cuba.core.app.queryresults;

import com.haulmont.bali.db.QueryRunner;
import com.haulmont.cuba.core.EntityManager;
import com.haulmont.cuba.core.Persistence;
import com.haulmont.cuba.core.Query;
import com.haulmont.cuba.core.Transaction;
import com.haulmont.cuba.core.app.ClusterManagerAPI;
import com.haulmont.cuba.core.app.DataServiceQueryBuilder;
import com.haulmont.cuba.core.global.*;
import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.core.sys.QueryHolder;
import com.haulmont.cuba.core.sys.persistence.DbTypeConverter;
import com.haulmont.cuba.security.global.UserSession;
import com.haulmont.cuba.security.app.UserSessionsAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Supports functionality that allows queries from previously selected results.
 */
@Component(QueryResultsManagerAPI.NAME)
public class QueryResultsManager implements QueryResultsManagerAPI {

    private final Logger log = LoggerFactory.getLogger(QueryResultsManager.class);

    @Inject
    protected Persistence persistence;

    @Inject
    protected UserSessionSource userSessionSource;

    @Inject
    protected UserSessionsAPI userSessions;

    @Inject
    protected ClusterManagerAPI clusterManager;

    @Inject
    protected Configuration configuration;

    @Inject
    protected Metadata metadata;

    private static final int BATCH_SIZE = 100;

    @Override
    public void savePreviousQueryResults(LoadContext loadContext) {
        List<LoadContext.Query> prevQueries = loadContext.getPrevQueries();
        if (prevQueries.isEmpty())
            return;

        LoadContext.Query contextQuery = prevQueries.get(prevQueries.size() - 1);
        String entityName = loadContext.getMetaClass();

        QueryParser parser = QueryTransformerFactory.createParser(contextQuery.getQueryString());
        if (!parser.isEntitySelect(entityName))
            return;

        int queryKey = loadContext.getQueryKey();

        if (resultsAlreadySaved(queryKey, contextQuery))
            return;

        List idList;
        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();
            em.setSoftDeletion(loadContext.isSoftDeletion());

            QueryTransformer transformer = QueryTransformerFactory.createTransformer(contextQuery.getQueryString());
            transformer.replaceWithSelectId(metadata.getTools().getPrimaryKeyName(metadata.getClassNN(entityName)));
            transformer.removeOrderBy();
            String queryString = transformer.getResult();

            DataServiceQueryBuilder queryBuilder = AppBeans.get(DataServiceQueryBuilder.NAME);
            queryBuilder.init(queryString, contextQuery.getParameters(), contextQuery.getNoConversionParams(), null, entityName);
            if (prevQueries.size() > 1) {
                queryBuilder.restrictByPreviousResults(userSessionSource.getUserSession().getId(), loadContext.getQueryKey());
            }
            Query query = queryBuilder.getQuery(em);

            String logMsg = "Load previous query results: " + DataServiceQueryBuilder.printQuery(query.getQueryString());
            log.debug(logMsg);
            long start = System.currentTimeMillis();

            idList = query.getResultList();
            tx.commit();

            log.debug("Done in " + (System.currentTimeMillis() - start) + "ms : " + logMsg);
        } finally {
            tx.end();
        }

        delete(queryKey);
        insert(queryKey, idList);
    }

    private boolean resultsAlreadySaved(Integer queryKey, LoadContext.Query query) {
        LinkedHashMap<Integer, QueryHolder> recentQueries =
                userSessionSource.getUserSession().getAttribute("_recentQueries");
        if (recentQueries == null) {
            recentQueries = new LinkedHashMap<Integer, QueryHolder>() {
                private static final long serialVersionUID = -901296839279897248L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, QueryHolder> eldest) {
                    return size() > 10;
                }
            };
        }

        QueryHolder queryHolder = new QueryHolder(query);
        QueryHolder oldQueryHolder = recentQueries.put(queryKey, queryHolder);

        // do not set to session attribute recentQueries directly, it contains reference to QueryResultsManager class
        // copy data to new LinkedHashMap
        userSessionSource.getUserSession().setAttribute("_recentQueries", new LinkedHashMap<>(recentQueries));

        return queryHolder.equals(oldQueryHolder);
    }

    @Override
    public void insert(int queryKey, List idList) {
        if (idList.isEmpty())
            return;

        UUID userSessionId = userSessionSource.getUserSession().getId();
        long start = System.currentTimeMillis();
        String logMsg = "Insert " + idList.size() + " query results for " + userSessionId + " / " + queryKey;
        log.debug(logMsg);

        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();
            DbTypeConverter converter = persistence.getDbTypeConverter();
            Object idFromList = idList.get(0);
            String columnName = null;
            if (idFromList instanceof String) {
                columnName = "STRING_ENTITY_ID";
            } else if (idFromList instanceof Long) {
                columnName = "LONG_ENTITY_ID";
            } else if (idFromList instanceof Integer) {
                columnName = "INT_ENTITY_ID";
            } else {
                columnName = "ENTITY_ID";
            }
            QueryRunner runner = new QueryRunner();
            try {
                String userSessionIdStr = converter.getSqlObject(userSessionId).toString(); // assuming that UUID can be passed to query as string in all databases
                String sql = String.format("insert into SYS_QUERY_RESULT (SESSION_ID, QUERY_KEY, %s) values ('%s', %s, ?)",
                        columnName, userSessionIdStr, queryKey);
                int[] paramTypes = new int[] { converter.getSqlType(idFromList.getClass()) };
                for (int i = 0; i < idList.size(); i += BATCH_SIZE) {
                    List<UUID> sublist = idList.subList(i, Math.min(i + BATCH_SIZE, idList.size()));
                    Object[][] params = new Object[sublist.size()][1];
                    for (int j = 0; j < sublist.size(); j++) {
                        params[j][0] = converter.getSqlObject(sublist.get(j));
                    }
                    runner.batch(em.getConnection(), sql, params, paramTypes);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            log.debug("Done in " + (System.currentTimeMillis() - start) + "ms: " + logMsg);

            tx.commit();
        } finally {
            tx.end();
        }
    }

    @Override
    public void delete(int queryKey) {
        DbTypeConverter converter = persistence.getDbTypeConverter();
        UUID userSessionId = userSessionSource.getUserSession().getId();
        String userSessionIdStr = converter.getSqlObject(userSessionId).toString();
        long start = System.currentTimeMillis();
        String logMsg = "Delete query results for " + userSessionId + " / " + queryKey;
        log.debug(logMsg);

        String sql = "delete from SYS_QUERY_RESULT where SESSION_ID = '"
                + userSessionIdStr + "' and QUERY_KEY = " + queryKey;

        QueryRunner runner = new QueryRunner(persistence.getDataSource());
        try {
            runner.update(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        log.debug("Done in " + (System.currentTimeMillis() - start) + "ms : " + logMsg);
    }

    @Override
    public void deleteForCurrentSession() {
        QueryRunner runner = new QueryRunner(persistence.getDataSource());
        try {
            DbTypeConverter converter = persistence.getDbTypeConverter();
            UUID userSessionId = userSessionSource.getUserSession().getId();
            String userSessionIdStr = converter.getSqlObject(userSessionId).toString();
            runner.update("delete from SYS_QUERY_RESULT where SESSION_ID = '"
                    + userSessionIdStr + "'");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteForInactiveSessions() {
        if (!AppContext.isStarted() || !clusterManager.isMaster()
                || !configuration.getConfig(GlobalConfig.class).getAllowQueryFromSelected())
            return;

        log.debug("Delete query results for inactive user sessions");

        StringBuilder sb = new StringBuilder("delete from SYS_QUERY_RESULT");
        Collection<UserSession> userSessionEntities = userSessions.getUserSessionsStream().collect(Collectors.toList());
        DbTypeConverter converter = persistence.getDbTypeConverter();
        if (!userSessionEntities.isEmpty()) {
            sb.append(" where SESSION_ID not in (");
            for (Iterator<UserSession> it = userSessionEntities.iterator(); it.hasNext(); ) {
                UserSession userSession = it.next();
                UUID userSessionId = userSession.getId();
                String userSessionIdStr = converter.getSqlObject(userSessionId).toString();
                sb.append("'").append(userSessionIdStr).append("'");
                if (it.hasNext())
                    sb.append(",");
            }
            sb.append(")");
        }
        QueryRunner runner = new QueryRunner(persistence.getDataSource());
        try {
            runner.update(sb.toString());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}