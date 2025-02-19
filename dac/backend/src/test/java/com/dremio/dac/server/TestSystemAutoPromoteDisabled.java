/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
 */
package com.dremio.dac.server;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Disable auto promotion from system env config and keep the source
 * to not auto promote, create table should work fine.
 */
public class TestSystemAutoPromoteDisabled extends TestSystemAutoPromotionBase {

  @BeforeClass
  public static void init() throws Exception {
    // Set the system property before starting dremio so it gets picked up
    // by com.dremio.exec.store.DatasetRetrievalOptions.DEFAULT_AUTO_PROMOTE_OPTIONAL
    System.setProperty("dremio.datasets.auto_promote", "false");
    TestSystemAutoPromotionBase.init();
  }

  @Test
  public void test() {
    run();
  }
}
