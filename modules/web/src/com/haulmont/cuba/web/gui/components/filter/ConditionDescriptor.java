/*
 * Copyright (c) 2009 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.

 * Author: Konstantin Krivopustov
 * Created: 15.10.2009 16:15:28
 *
 * $Id$
 */
package com.haulmont.cuba.web.gui.components.filter;

import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.cuba.gui.data.CollectionDatasource;
import com.haulmont.cuba.core.global.QueryParser;
import com.haulmont.cuba.core.global.QueryTransformerFactory;
import org.dom4j.Element;

public abstract class ConditionDescriptor {

    protected Element element;
    protected String name;
    protected String caption;
    protected String locCaption;
    protected String filterComponentName;
    protected MetaClass metaClass;
    protected CollectionDatasource datasource;
    protected String entityAlias;

    public ConditionDescriptor(String name, String filterComponentName, CollectionDatasource datasource) {
        this.name = name;
        this.filterComponentName = filterComponentName;
        this.datasource = datasource;
        this.metaClass = datasource.getMetaClass();

        String query = datasource.getQuery();
        QueryParser parser = QueryTransformerFactory.createParser(query);
        entityAlias = parser.getEntityAlias(metaClass.getName());
    }

    public String getName() {
        return name;
    }

    public String getCaption() {
        return caption;
    }

    public String getLocCaption() {
        return locCaption;
    }

    public String getFilterComponentName() {
        return filterComponentName;
    }

    public MetaClass getMetaClass() {
        return metaClass;
    }

    public Param createParam(Condition condition) {
        Param param = new Param(condition.createParamName(), getJavaClass(),
                getEntityParamWhere(), getEntityParamView(), datasource);
        return param;
    }

    public CollectionDatasource getDatasource() {
        return datasource;
    }

    public abstract Condition createCondition();

    public abstract Class getJavaClass();

    public abstract String getEntityParamWhere();

    public abstract String getEntityParamView();
}
