/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.spring;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.srcMainResources;
import static org.openrewrite.yaml.Assertions.yaml;

@Disabled
class SeparateApplicationYamlByProfileTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new SeparateApplicationYamlByProfile());
    }

    @Test
    void separateProfile() {
        rewriteRun(
          //language=yaml
          srcMainResources(
            yaml(
              """
                name: main
                ---
                spring:
                  config:
                    activate:
                      on-profile: test
                name: test
                """,
              "name: main",
              spec -> spec.path("application.yaml")
            ),
            yaml(
              null,
              "name: test",
              spec -> spec.path("application-test.yaml")
            )
          )
        );
    }

    @Test
    void leaveProfileExpressionsAlone() {
        rewriteRun(
          srcMainResources(
            yaml(
              //language=yaml
              """
                spring:
                  config:
                    activate:
                      on-profile: !test
                name: test
                """,
              spec -> spec.path("application.yaml")
            )
          )
        );
    }
}
