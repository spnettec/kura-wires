/*******************************************************************************
 * Copyright (c) 2022 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 *******************************************************************************/
package org.eclipse.kura.wire.script.tools.conditional.component;

import java.util.Map;
import java.util.Optional;

public class ConditionalComponentOptions {

    public static final String CONDITION_PROPERTY_KEY = "condition";
    public static final String LANGUAGE_KEY = "language";
    public static final String LANGUAGE_DEFAULT_VALUE = "js";

    private String booleanExpression;
    private String language;

    ConditionalComponentOptions(final Map<String, Object> properties) {
        this.booleanExpression = (String) properties.get(CONDITION_PROPERTY_KEY);
        this.booleanExpression = this.booleanExpression == null ? "" : this.booleanExpression.trim();

        this.language = (String) properties.getOrDefault(LANGUAGE_KEY, LANGUAGE_DEFAULT_VALUE);
    }

    Optional<String> getBooleanExpression() {
        if (this.booleanExpression.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(this.booleanExpression);
    }

    String getLanguage() {
        return language;
    }

}
