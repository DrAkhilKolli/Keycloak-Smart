/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
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

package org.keycloak.quarkus.runtime.themes;

import org.keycloak.theme.ClasspathThemeProviderFactory;

public class QuarkusThemesRepresentation {

    private ThemeRepresentation[] themes;

    public ThemeRepresentation[] getThemes() {
        return themes;
    }

    public void setThemes(ThemeRepresentation[] themes) {
        this.themes = themes;
    }

    ClasspathThemeProviderFactory.ThemesRepresentation toClasspathThemes() {
        ClasspathThemeProviderFactory.ThemesRepresentation representation = new ClasspathThemeProviderFactory.ThemesRepresentation();

        if (themes == null) {
            representation.setThemes(new ClasspathThemeProviderFactory.ThemeRepresentation[0]);
            return representation;
        }

        ClasspathThemeProviderFactory.ThemeRepresentation[] converted = new ClasspathThemeProviderFactory.ThemeRepresentation[themes.length];
        for (int index = 0; index < themes.length; index++) {
            ThemeRepresentation theme = themes[index];
            ClasspathThemeProviderFactory.ThemeRepresentation convertedTheme = new ClasspathThemeProviderFactory.ThemeRepresentation();
            convertedTheme.setName(theme.getName());
            convertedTheme.setTypes(theme.getTypes());
            converted[index] = convertedTheme;
        }

        representation.setThemes(converted);
        return representation;
    }

    public static class ThemeRepresentation {
        private String name;
        private String[] types;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String[] getTypes() {
            return types;
        }

        public void setTypes(String[] types) {
            this.types = types;
        }
    }
}