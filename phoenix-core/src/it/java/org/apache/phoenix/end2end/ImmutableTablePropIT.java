/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.SchemaUtil;
import org.junit.Test;

public class ImmutableTablePropIT extends ParallelStatsDisabledIT {

    @Test
    public void testImmutableKeyword() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        String immutableDataTableFullName = SchemaUtil.getTableName("", generateUniqueName());
        String mutableDataTableFullName = SchemaUtil.getTableName("", generateUniqueName());
        try (Connection conn = DriverManager.getConnection(getUrl(), props);) {
            Statement stmt = conn.createStatement();
            // create table with immutable keyword
            String ddl = "CREATE IMMUTABLE TABLE  " + immutableDataTableFullName +
                    "  (a_string varchar not null, col1 integer" +
                    "  CONSTRAINT pk PRIMARY KEY (a_string)) STORE_NULLS=true";
            stmt.execute(ddl);
            
            // create table without immutable keyword
            ddl = "CREATE TABLE  " + mutableDataTableFullName +
                    "  (a_string varchar not null, col1 integer" +
                    "  CONSTRAINT pk PRIMARY KEY (a_string)) STORE_NULLS=true";
            stmt.execute(ddl);
            
            PhoenixConnection phxConn = conn.unwrap(PhoenixConnection.class);
            PTable immutableTable = phxConn.getTable(new PTableKey(null, immutableDataTableFullName));
            assertTrue("IMMUTABLE_ROWS should be set to true", immutableTable.isImmutableRows());
            PTable mutableTable = phxConn.getTable(new PTableKey(null, mutableDataTableFullName));
            assertFalse("IMMUTABLE_ROWS should be set to false", mutableTable.isImmutableRows());
        } 
    }
    
    @Test
    public void testImmutableProperty() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        String immutableDataTableFullName = SchemaUtil.getTableName("", generateUniqueName());
        String mutableDataTableFullName = SchemaUtil.getTableName("", generateUniqueName());
        try (Connection conn = DriverManager.getConnection(getUrl(), props);) {
            Statement stmt = conn.createStatement();
            // create table with immutable_table property set to true
            String ddl = "CREATE TABLE  " + immutableDataTableFullName +
                    "  (a_string varchar not null, col1 integer" +
                    "  CONSTRAINT pk PRIMARY KEY (a_string)) IMMUTABLE_ROWS=true";
            stmt.execute(ddl);
            
            // create table with immutable_table property set to false
            ddl = "CREATE TABLE  " + mutableDataTableFullName +
                    "  (a_string varchar not null, col1 integer" +
                    "  CONSTRAINT pk PRIMARY KEY (a_string))  IMMUTABLE_ROWS=false";
            stmt.execute(ddl);
            
            PhoenixConnection phxConn = conn.unwrap(PhoenixConnection.class);
            PTable immutableTable = phxConn.getTable(new PTableKey(null, immutableDataTableFullName));
            assertTrue("IMMUTABLE_ROWS should be set to true", immutableTable.isImmutableRows());
            PTable mutableTable = phxConn.getTable(new PTableKey(null, mutableDataTableFullName));
            assertFalse("IMMUTABLE_ROWS should be set to false", mutableTable.isImmutableRows());
        } 
    }
    
}
