// Modifications copyright (C) 2017, Baidu.com, Inc.
// Copyright 2017 The Apache Software Foundation

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.baidu.palo.qe;

import com.baidu.palo.analysis.AccessTestUtil;
import com.baidu.palo.analysis.Analyzer;
import com.baidu.palo.analysis.DescribeStmt;
import com.baidu.palo.analysis.HelpStmt;
import com.baidu.palo.analysis.SetType;
import com.baidu.palo.analysis.ShowAuthorStmt;
import com.baidu.palo.analysis.ShowColumnStmt;
import com.baidu.palo.analysis.ShowCreateDbStmt;
import com.baidu.palo.analysis.ShowCreateTableStmt;
import com.baidu.palo.analysis.ShowDbStmt;
import com.baidu.palo.analysis.ShowEnginesStmt;
import com.baidu.palo.analysis.ShowProcedureStmt;
import com.baidu.palo.analysis.ShowProcesslistStmt;
import com.baidu.palo.analysis.ShowTableStmt;
import com.baidu.palo.analysis.ShowVariablesStmt;
import com.baidu.palo.analysis.TableName;
import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Column;
import com.baidu.palo.catalog.Database;
import com.baidu.palo.catalog.KeysType;
import com.baidu.palo.catalog.MaterializedIndex;
import com.baidu.palo.catalog.OlapTable;
import com.baidu.palo.catalog.Partition;
import com.baidu.palo.catalog.PrimitiveType;
import com.baidu.palo.catalog.RandomDistributionInfo;
import com.baidu.palo.catalog.SinglePartitionInfo;
import com.baidu.palo.catalog.Table.TableType;
import com.baidu.palo.system.SystemInfoService;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.InternalException;
import com.baidu.palo.common.PatternMatcher;
import com.baidu.palo.thrift.TStorageType;
import com.baidu.palo.mysql.MysqlCommand;

import com.google.common.collect.Lists;

import org.junit.Assert;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.IOException;
import java.net.URL;
import java.util.List;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({ "org.apache.log4j.*", "javax.management.*" })
@PrepareForTest({ ShowExecutor.class, Catalog.class, VariableMgr.class, HelpModule.class })
public class ShowExecutorTest {
    private ConnectContext ctx;
    private Catalog catalog;

    @Before
    public void setUp() throws Exception {
        ctx = new ConnectContext(null);
        ctx.setCommand(MysqlCommand.COM_SLEEP);

        Column column1 = new Column("col1", PrimitiveType.BIGINT);
        Column column2 = new Column("col2", PrimitiveType.DOUBLE);
        column1.setIsKey(true);
        column2.setIsKey(true);
        // mock index 1
        MaterializedIndex index1 = EasyMock.createMock(MaterializedIndex.class);
        EasyMock.replay(index1);
        // mock index 2
        MaterializedIndex index2 = EasyMock.createMock(MaterializedIndex.class);
        EasyMock.replay(index2);

        // mock partition
        Partition partition = EasyMock.createMock(Partition.class);
        EasyMock.expect(partition.getRollupIndices()).andReturn(Lists.newArrayList(index1, index2)).anyTimes();
        EasyMock.expect(partition.getBaseIndex()).andReturn(index1).anyTimes();
        EasyMock.replay(partition);

        // mock table
        OlapTable table = EasyMock.createMock(OlapTable.class);
        EasyMock.expect(table.getName()).andReturn("testTbl").anyTimes();
        EasyMock.expect(table.getType()).andReturn(TableType.OLAP).anyTimes();
        EasyMock.expect(table.getBaseSchema()).andReturn(Lists.newArrayList(column1, column2)).anyTimes();
        EasyMock.expect(table.getKeysType()).andReturn(KeysType.AGG_KEYS);
        EasyMock.expect(table.getPartitionInfo()).andReturn(new SinglePartitionInfo()).anyTimes();
        EasyMock.expect(table.getDefaultDistributionInfo()).andReturn(new RandomDistributionInfo(10)).anyTimes();
        EasyMock.expect(table.getIndexIdByName(EasyMock.isA(String.class))).andReturn(0L).anyTimes();
        EasyMock.expect(table.getStorageTypeByIndexId(0L)).andReturn(TStorageType.COLUMN).anyTimes();
        EasyMock.expect(table.getPartition(EasyMock.anyLong())).andReturn(partition).anyTimes();
        EasyMock.expect(table.getCopiedBfColumns()).andReturn(null);
        EasyMock.replay(table);

        // mock database
        Database db = EasyMock.createMock(Database.class);
        db.readLock();
        EasyMock.expectLastCall().anyTimes();
        db.readUnlock();
        EasyMock.expectLastCall().anyTimes();
        EasyMock.expect(db.getTable(EasyMock.isA(String.class))).andReturn(table).anyTimes();
        EasyMock.replay(db);

        // mock catalog.
        catalog = EasyMock.createMock(Catalog.class);
        EasyMock.expect(catalog.getDb("testCluster:testDb")).andReturn(db).anyTimes();
        EasyMock.expect(catalog.getDb("testCluster:emptyDb")).andReturn(null).anyTimes();
        EasyMock.expect(catalog.getClusterDbNames("testCluster")).andReturn(Lists.newArrayList("testCluster:testDb"))
                .anyTimes();
        EasyMock.expect(catalog.getClusterDbNames("")).andReturn(Lists.newArrayList("")).anyTimes();
        EasyMock.replay(catalog);
        PowerMock.expectNew(Catalog.class).andReturn(catalog).anyTimes();
        PowerMock.replay(Catalog.class);

        // mock scheduler
        ConnectScheduler scheduler = EasyMock.createMock(ConnectScheduler.class);
        EasyMock.expect(scheduler.listConnection("testCluster:testUser"))
                .andReturn(Lists.newArrayList(ctx.toThreadInfo())).anyTimes();
        EasyMock.replay(scheduler);
        ctx.setConnectScheduler(scheduler);
        ctx.setCatalog(AccessTestUtil.fetchAdminCatalog());
        ctx.setUser("testCluster:testUser");
        ctx.setCluster("testCluster");
    }

    @Test
    public void testShowDb() throws AnalysisException {
        ShowDbStmt stmt = new ShowDbStmt(null);
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("testDb", resultSet.getString(0));
    }

    @Test
    public void testShowDbPattern() throws AnalysisException {
        ShowDbStmt stmt = new ShowDbStmt("testCluster:empty%");
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testShowDbPriv() throws AnalysisException {
        ShowDbStmt stmt = new ShowDbStmt(null);
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ctx.setCatalog(AccessTestUtil.fetchBlockCatalog());
        ShowResultSet resultSet = executor.execute();

        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testShowTable() throws AnalysisException {
        ShowTableStmt stmt = new ShowTableStmt("testCluster:testDb", false, null);
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("testTbl", resultSet.getString(0));
        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testShowTableEmpty() throws AnalysisException {
        ShowTableStmt stmt = new ShowTableStmt("testCluster:emptyDb", false, null);
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testShowTablePattern() throws AnalysisException {
        ShowTableStmt stmt = new ShowTableStmt("testCluster:testDb", false, "empty%");
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testDescribe() {
        SystemInfoService clusterInfo = EasyMock.createMock(SystemInfoService.class);
        EasyMock.replay(clusterInfo);

        Analyzer analyzer = AccessTestUtil.fetchAdminAnalyzer(false);
        Catalog catalog = AccessTestUtil.fetchAdminCatalog();
        PowerMock.mockStatic(Catalog.class);
        EasyMock.expect(Catalog.getInstance()).andReturn(catalog).anyTimes();
        EasyMock.expect(Catalog.getCurrentSystemInfo()).andReturn(clusterInfo).anyTimes();
        PowerMock.replay(Catalog.class);

        DescribeStmt stmt = new DescribeStmt(new TableName("testCluster:testDb", "testTbl"), false);
        try {
            stmt.analyze(analyzer);
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }

        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet;
        try {
            resultSet = executor.execute();
            Assert.assertFalse(resultSet.next());
        } catch (AnalysisException e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void testShowVariable() throws AnalysisException {
        // Mock variable
        PowerMock.mockStatic(VariableMgr.class);
        List<List<String>> rows = Lists.newArrayList();
        rows.add(Lists.newArrayList("var1", "abc"));
        rows.add(Lists.newArrayList("var2", "abc"));
        EasyMock.expect(VariableMgr.dump(EasyMock.isA(SetType.class), EasyMock.isA(SessionVariable.class),
                EasyMock.isA(PatternMatcher.class))).andReturn(rows).anyTimes();
        EasyMock.expect(VariableMgr.dump(EasyMock.isA(SetType.class), EasyMock.isA(SessionVariable.class),
                EasyMock.<PatternMatcher> isNull())).andReturn(rows).anyTimes();
        PowerMock.replay(VariableMgr.class);

        ShowVariablesStmt stmt = new ShowVariablesStmt(SetType.SESSION, "var%");
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("var1", resultSet.getString(0));
        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("var2", resultSet.getString(0));
        Assert.assertFalse(resultSet.next());

        stmt = new ShowVariablesStmt(SetType.SESSION, null);
        executor = new ShowExecutor(ctx, stmt);
        resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("var1", resultSet.getString(0));
        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("var2", resultSet.getString(0));
        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testShowTableVerbose() throws AnalysisException {
        ShowTableStmt stmt = new ShowTableStmt("testCluster:testDb", true, null);
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("testTbl", resultSet.getString(0));
        Assert.assertEquals("BASE TABLE", resultSet.getString(1));
        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testShowCreateDb() throws AnalysisException {
        ctx.setCatalog(catalog);
        ctx.setUser("testCluster:testUser");

        ShowCreateDbStmt stmt = new ShowCreateDbStmt("testCluster:testDb");
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("testDb", resultSet.getString(0));
        Assert.assertEquals("CREATE DATABASE `testDb`", resultSet.getString(1));
        Assert.assertFalse(resultSet.next());
    }

    @Test(expected = AnalysisException.class)
    public void testShowCreateNoDb() throws AnalysisException {
        ctx.setCatalog(catalog);
        ctx.setUser("testCluster:testUser");

        ShowCreateDbStmt stmt = new ShowCreateDbStmt("testCluster:emptyDb");
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.fail("No exception throws.");
    }

    @Test
    public void testShowCreateTable() throws AnalysisException {
        ctx.setCatalog(catalog);
        ctx.setUser("testCluster:testUser");

        ShowCreateTableStmt stmt = new ShowCreateTableStmt(new TableName("testCluster:testDb", "testTbl"));
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("testTbl", resultSet.getString(0));

        // print to help compare
        String result = new String(resultSet.getString(1));
        result = result.replace(' ', '*');
        System.out.println("create table stmt:[" + result + "]");

        Assert.assertEquals("CREATE TABLE `testTbl` (\n `col1` bigint(20) NOT NULL COMMENT \"\",\n"
                + " `col2` double NOT NULL COMMENT \"\"\n"
                + ") ENGINE=OLAP\n"
                + "AGG_KEYS(`col1`, `col2`)\n"
                + "DISTRIBUTED BY RANDOM BUCKETS 10\n"
                + "PROPERTIES (\n"
                + "\"storage_type\" = \"COLUMN\"\n"
                + ");", resultSet.getString(1));
    }

    @Test(expected = AnalysisException.class)
    public void testShowCreateTableEmptyDb() throws AnalysisException {
        ShowCreateTableStmt stmt = new ShowCreateTableStmt(new TableName("testCluster:emptyDb", "testTable"));
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.fail("No Exception throws.");
    }

    @Test(expected = AnalysisException.class)
    public void testShowCreateTableEmptyTbl() throws AnalysisException {
        ShowCreateTableStmt stmt = new ShowCreateTableStmt(new TableName("testCluster:testDb", "emptyTable"));
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testShowColumn() throws AnalysisException {
        ctx.setCatalog(catalog);
        ctx.setUser("testCluster:testUser");
        ShowColumnStmt stmt = new ShowColumnStmt(new TableName("testCluster:testDb", "testTbl"), null, null, false);
        stmt.analyze(AccessTestUtil.fetchAdminAnalyzer(false));
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("col1", resultSet.getString(0));
        Assert.assertEquals("NO", resultSet.getString(2));
        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("col2", resultSet.getString(0));
        Assert.assertFalse(resultSet.next());

        // verbose
        stmt = new ShowColumnStmt(new TableName("testCluster:testDb", "testTbl"), null, null, true);
        stmt.analyze(AccessTestUtil.fetchAdminAnalyzer(false));
        executor = new ShowExecutor(ctx, stmt);
        resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("col1", resultSet.getString(0));
        Assert.assertEquals("NO", resultSet.getString(3));
        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("col2", resultSet.getString(0));
        Assert.assertEquals("NO", resultSet.getString(3));
        Assert.assertFalse(resultSet.next());

        // pattern
        stmt = new ShowColumnStmt(new TableName("testCluster:testDb", "testTable"), null, "%1", true);
        stmt.analyze(AccessTestUtil.fetchAdminAnalyzer(false));
        executor = new ShowExecutor(ctx, stmt);
        resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("col1", resultSet.getString(0));
        Assert.assertEquals("NO", resultSet.getString(3));
        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testShowColumnEmpty() throws AnalysisException {
        ShowColumnStmt stmt = new ShowColumnStmt(new TableName("testCluster:emptyDb", "testTable"), null, null, false);
        stmt.analyze(AccessTestUtil.fetchAdminAnalyzer(false));
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertFalse(resultSet.next());

        // empty table
        stmt = new ShowColumnStmt(new TableName("testCluster:testDb", "emptyTable"), null, null, true);
        stmt.analyze(AccessTestUtil.fetchAdminAnalyzer(false));
        executor = new ShowExecutor(ctx, stmt);
        resultSet = executor.execute();

        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testShowAuthors() throws AnalysisException {
        ShowAuthorStmt stmt = new ShowAuthorStmt();
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertEquals(3, resultSet.getMetaData().getColumnCount());
        Assert.assertEquals("Name", resultSet.getMetaData().getColumn(0).getName());
        Assert.assertEquals("Location", resultSet.getMetaData().getColumn(1).getName());
        Assert.assertEquals("Comment", resultSet.getMetaData().getColumn(2).getName());

        Assert.assertTrue(resultSet.next());
    }

    @Test
    public void testShowEngine() throws AnalysisException {
        ShowEnginesStmt stmt = new ShowEnginesStmt();
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("Olap engine", resultSet.getString(0));
    }

    @Test
    public void testShowEmpty() throws AnalysisException {
        ShowProcedureStmt stmt = new ShowProcedureStmt();
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testShowProcesslist() throws AnalysisException {
        ShowProcesslistStmt stmt = new ShowProcesslistStmt();
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testHelp() throws AnalysisException, IOException, InternalException {
        HelpModule module = new HelpModule();
        URL help = getClass().getClassLoader().getResource("test-help-resource-show-help.zip");
        module.setUpByZip(help.getPath());
        PowerMock.mockStatic(HelpModule.class);
        EasyMock.expect(HelpModule.getInstance()).andReturn(module).anyTimes();
        PowerMock.replay(HelpModule.class);

        // topic
        HelpStmt stmt = new HelpStmt("ADD");
        ShowExecutor executor = new ShowExecutor(ctx, stmt);
        ShowResultSet resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("ADD", resultSet.getString(0));
        Assert.assertEquals("add function\n", resultSet.getString(1));
        Assert.assertFalse(resultSet.next());

        // topic
        stmt = new HelpStmt("logical");
        executor = new ShowExecutor(ctx, stmt);
        resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("OR", resultSet.getString(0));
        Assert.assertFalse(resultSet.next());

        // keywords
        stmt = new HelpStmt("MATH");
        executor = new ShowExecutor(ctx, stmt);
        resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("ADD", resultSet.getString(0));
        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("MINUS", resultSet.getString(0));
        Assert.assertFalse(resultSet.next());

        // category
        stmt = new HelpStmt("functions");
        executor = new ShowExecutor(ctx, stmt);
        resultSet = executor.execute();

        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("HELP", resultSet.getString(0));
        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("binary function", resultSet.getString(0));
        Assert.assertTrue(resultSet.next());
        Assert.assertEquals("bit function", resultSet.getString(0));
        Assert.assertFalse(resultSet.next());

        // empty
        stmt = new HelpStmt("empty");
        executor = new ShowExecutor(ctx, stmt);
        resultSet = executor.execute();

        Assert.assertFalse(resultSet.next());
    }
}
