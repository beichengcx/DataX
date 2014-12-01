package com.alibaba.datax.plugin.writer.otswriter.unittest;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.datax.common.element.LongColumn;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.core.transport.record.DefaultRecord;
import com.alibaba.datax.plugin.writer.otswriter.common.Conf;
import com.alibaba.datax.plugin.writer.otswriter.common.TestPluginCollector;
import com.alibaba.datax.plugin.writer.otswriter.common.Utils;
import com.alibaba.datax.plugin.writer.otswriter.model.OTSBatchWriteRowTaskManager;
import com.alibaba.datax.plugin.writer.otswriter.model.OTSConf;
import com.alibaba.datax.plugin.writer.otswriter.model.OTSLine;
import com.alibaba.datax.plugin.writer.otswriter.model.OTSOpType;
import com.aliyun.openservices.ots.model.ColumnType;
import com.aliyun.openservices.ots.model.ColumnValue;
import com.aliyun.openservices.ots.model.MockOTSClient;
import com.aliyun.openservices.ots.model.OTSRow;
import com.aliyun.openservices.ots.model.PrimaryKeyType;
import com.aliyun.openservices.ots.model.PrimaryKeyValue;

/**
 * 1.并发数目
 *      1.1 1个并发
 *      1.2 5个并发
 * 2.立即关闭Manager
 * 3.写入压力
 *      3.1 间歇性
 *      3.2 持续性
 */
public class OTSBatchWriteRowTaskManagerUnittest {
    
    private static String tableName = "OTSBatchWriteRowTaskManagerUnittest";
    public static List<PrimaryKeyType> pk = new ArrayList<PrimaryKeyType>();
    public static List<ColumnType> attr = new ArrayList<ColumnType>();
    
    @BeforeClass
    public static void init() {
        pk.add(PrimaryKeyType.INTEGER);
        attr.add(ColumnType.INTEGER);
    }
    
    public void test(long interval, int lineCount, int concurrency, int invokeTimes) throws Exception {
        OTSConf conf = Conf.getConf(tableName, pk, attr, OTSOpType.UPDATE_ROW);
        conf.setConcurrencyWrite(concurrency);
        Configuration configuration = Configuration.newDefault();
        TestPluginCollector collector = new TestPluginCollector(configuration, null, null);
        MockOTSClient ots = new MockOTSClient(5000, null, null, 10);
        OTSBatchWriteRowTaskManager manager = new OTSBatchWriteRowTaskManager(ots, collector, conf);
        
        List<OTSRow> expect = new ArrayList<OTSRow>();
        
        for (int i = 0; i < lineCount; i++) {
            Record r = new DefaultRecord();
            r.addColumn(new LongColumn(i));
            r.addColumn(new LongColumn(i));
            OTSLine line = new OTSLine(tableName, OTSOpType.UPDATE_ROW, r, conf.getPrimaryKeyColumn(), conf.getAttributeColumn());
            List<OTSLine> lines = new ArrayList<OTSLine>();
            lines.add(line);
            if (interval > 0) {
                Thread.sleep(interval);
            }
            manager.execute(lines);
            
            OTSRow row = new OTSRow();
            row.addPK("pk_0", PrimaryKeyValue.fromLong(i));
            row.addColumn("attr_0", ColumnValue.fromLong(i));
            expect.add(row);
        }
        manager.close();
        
        assertEquals(0, collector.getContent().size());
        assertEquals(true, Utils.checkInput(ots, expect)); 
        assertEquals(invokeTimes, ots.getInvokeTimes());
        assertEquals(concurrency, ots.getMaxConcurrenyInvokeTimes());
    }

    // 输入：设置1个并发，间歇性的写入Task，写完Task之后立即关闭Manager，期望：SDK内部记录到的并发为1，数据正常（覆盖场景：1.1、3.1、2）
    @Test
    public void testCase1() throws Exception {
        test(100, 300, 1, 300);
    }
    // 输入：设置1个并发，持续不断的写入Task，写完Task之后立即关闭Manager，期望：SDK内部记录到的并发为1，数据正常（覆盖场景：1.1、3.2、2）
    @Test
    public void testCase2() throws Exception {
        test(0, 300, 1, 300);
    }
    
    // 输入：设置5个并发数据，间歇性的写入Task，写完Task之后立即关闭Manager，期望：SDK内部记录到的并发小于等于1，数据正常（覆盖场景：1.2、3.1、2）
    @Test
    public void testCase3() throws Exception {
        test(100, 300, 1, 300);
    }
    
    // 输入：设置5个并发数据，持续性的写入Task，写完Task之后立即关闭Manager，期望：SDK内部记录到的并发小于等于5，数据正常（覆盖场景：1.2、3.2、2）
    @Test
    public void testCase4() throws Exception {
        test(0, 300, 5, 300);
    }
    
    // 输入：在写入过程中主动关闭Manager,期望：上层能收到Manager关闭的异常
    @Test
    public void testCase5() throws Exception {
        OTSConf conf = Conf.getConf(tableName, pk, attr, OTSOpType.UPDATE_ROW);
        Configuration configuration = Configuration.newDefault();
        TestPluginCollector collector = new TestPluginCollector(configuration, null, null);
        MockOTSClient ots = new MockOTSClient();
        OTSBatchWriteRowTaskManager manager = new OTSBatchWriteRowTaskManager(ots, collector, conf);
        manager.close();
        
        List<OTSLine> lines = new ArrayList<OTSLine>();
        {
            Record r = new DefaultRecord();
            r.addColumn(new LongColumn(2));
            r.addColumn(new LongColumn(2));
            OTSLine line = new OTSLine(tableName, OTSOpType.UPDATE_ROW, r, conf.getPrimaryKeyColumn(), conf.getAttributeColumn());
            lines.add(line);
        }

        try {
            manager.execute(lines);
            assertTrue(false);
        } catch (Exception e) {
            assertEquals("Can not execute the task, becase the ExecutorService is shutdown.", e.getMessage());
        }
    }
}
