/**
* Project Name:KettleUtil
* Date:2016年6月20日下午12:39:31
* Copyright (c) 2016, jingma@iflytek.com All Rights Reserved.
*/

package com.metl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Calendar;

import org.apache.commons.lang3.StringUtils;
import org.pentaho.di.job.entries.eval.JobEntryEval;

import com.alibaba.fastjson.JSONObject;
import com.metl.util.CommonUtil;
import com.metl.util.Constants;
import com.metl.util.DateUtil;
import com.metl.util.Db;

/**
 * 生成数据账单 <br/>
 * date: 2016年6月20日 下午12:39:31 <br/>
 * @author jingma@iflytek.com
 * @version 
 */
public class GenerateDataBill {
    /**
    * 数据任务
    */
    private JSONObject dataTask;
    /**
    * 来源对象
    */
    private JSONObject sourceObj;
    /**
    * javascript控件
    */
    private JobEntryEval jee;
    /**
    * 项目基础数据库操作对象
    */
    private Db metldb;
    
    /**
     * Creates a new instance of GenerateDataBill.
     */
    public GenerateDataBill() {
    }
    
    public GenerateDataBill(JobEntryEval jobEntryEval) {
        super();
        this.jee = jobEntryEval;
        this.dataTask = CommonUtil.getPropJSONObject(jee,"dataTask");
        this.sourceObj = CommonUtil.getPropJSONObject(jee,"sourceObj");
        metldb = Db.getDb(jee, Constants.DATASOURCE_METL);
    }

    /**
    * 开始生成数据账单 <br/>
    * @author jingma@iflytek.com
    */
    public void run(){
        String doType = sourceObj.getString("do_type");
        jee.logBasic("来源对象类型："+doType);
        switch (doType) {
        case "table":
            gdbTable();
            break;
        default:
            break;
        }
        
    }

    /**
    * 生成数据账单-表 <br/>
    * @author jingma@iflytek.com
    */
    private void gdbTable() {
        String isAdd = dataTask.getString("is_add");
        //增量
        if(Constants.WHETHER_TRUE.equals(isAdd)){
            String dbCode = sourceObj.getString("database");
            JSONObject addField = metldb.findOne("select * from metl_data_field t where t.oid=?", 
                    sourceObj.getString("add_field"));
            //增量字段支持的业务类型是：时间、数字
            String dataType = addField.getString("data_type");
            String businessType = addField.getString("business_type");
            //实时从数据库查询抽取标记
            String etlflag = metldb.findOne("select etlflag from metl_data_task t where t.ocode=?", 
                    dataTask.getString("ocode")).getString("etlflag");
            Db sourcedb = Db.getDb(jee, dbCode);
            //如果抽取标记为空，则从来源对象获取增量字段最小值
            JSONObject minObj = null;
            JSONObject maxObj = null;
            if(StringUtils.isBlank(etlflag)){
                minObj = sourcedb.findOne("select min("+addField.getString("ocode")
                        +") as etlflag from "+sourceObj.getString("real_name"));
                //数据类型是date
                if(Constants.FD_TYPE_DATE.equals(dataType)){
                    etlflag = DateUtil.doFormatDate(minObj.getDate("etlflag"),
                            DateUtil.DATE_FORMATTER14);
                }else{
                    //否则数据类型就是字符串，这些限制需要在前端配置时做好校验
                    etlflag = minObj.getString("etlflag");
                }
            }
            //若抽取标识为空，则结束
            if(StringUtils.isBlank(etlflag)){
                jee.logError(dataTask+"任务来源对象没有数据");
                return;
            }
            //数据类型是date
            if(Constants.FD_TYPE_DATE.equals(dataType)){
                //获取来源对象中增量字段最大值，小于当前时间
                maxObj = sourcedb.findOne("select max("+addField.getString("ocode")
                        +") as etlflag from "+sourceObj.getString("real_name")+
                        " t where t."+addField.getString("ocode")+"<sysdate");
            }else{
                //获取来源对象中增量字段最大值，小于当前时间
                maxObj = sourcedb.findOne("select max("+addField.getString("ocode")
                        +") as etlflag from "+sourceObj.getString("real_name")+
                        " t where t."+addField.getString("ocode")
                        +"<to_char(sysdate,'yyyymmddhh24miss')");
            }
            String tempEtlflag = null;
            //业务类型是时间
            if(Constants.FB_TYPE_DATE.equals(businessType)){
                //首次，对历史数据进行分片处理
                if(minObj != null){
                    //开始
                    Calendar start = Calendar.getInstance();
                    //结束
                    Calendar end = Calendar.getInstance();

                    //数据类型是date
                    if(Constants.FD_TYPE_DATE.equals(dataType)){
                        start.setTime(minObj.getDate("etlflag"));
                        end.setTime(maxObj.getDate("etlflag"));
                    }else{
                        //否则数据类型就是字符串(现在只认14位长度的时间)，这些限制需要在前端配置时做好校验
                        start.setTime(DateUtil.parseDate(minObj.getString("etlflag")));
                        end.setTime(DateUtil.parseDate(maxObj.getString("etlflag")));
                    }
                    //增量，以天为单位
                    Integer addInterval = sourceObj.getInteger("add_interval");
                    while(true){
                        start.add(Calendar.DAY_OF_MONTH, addInterval);
                        tempEtlflag = DateUtil.doFormatDate(start.getTime(),
                                DateUtil.DATE_FORMATTER14);
                        //如果还没有达到最大值
                        if(start.before(end)){
                            //在数据账单中添加记录
                            addDataBillTable(etlflag,tempEtlflag);
                            etlflag = tempEtlflag;
                        }else if(start.equals(end)||start.after(end)){
                            tempEtlflag = DateUtil.doFormatDate(end.getTime(),
                                    DateUtil.DATE_FORMATTER14);
                            //在数据账单中添加记录
                            addDataBillTable(etlflag,tempEtlflag);
                            etlflag = tempEtlflag;
                            break;
                        }
                    }
                }else{
                    //若已经有数据账单生成过了，则直接生成增量账单
                    //数据类型是date
                    if(Constants.FD_TYPE_DATE.equals(dataType)){
                        tempEtlflag = DateUtil.doFormatDate(maxObj.getDate("etlflag"),
                                DateUtil.DATE_FORMATTER14);
                    }else{
                        //否则数据类型就是字符串(现在只认14位长度的时间)，这些限制需要在前端配置时做好校验
                        tempEtlflag = maxObj.getString("etlflag");
                    }
                    if(!etlflag.equals(tempEtlflag)){
                        //在数据账单中添加记录
                        addDataBillTable(etlflag,tempEtlflag);
                    }
                }
            }else{
                //否则就是数字，本系统增量字段业务类型支持时间和数字
                
            }
            //更新抽取标记
            String sql = "update METL_DATA_TASK t set t.etlflag='"
            +tempEtlflag+"' where t.oid='"+dataTask.getString("oid")+"'";
            metldb.execute(sql);
        }else{
            //否则就不是增量
            
        }
    }

    /**
    * 添加数据账单-表 <br/>
    * @author jingma@iflytek.com
    * @param start
    * @param end
    */
    private void addDataBillTable(String start, String end) {
        String sql = "insert into metl_data_bill (create_user, source_task, "
                + "source_obj, target_obj, job, database, source_table, "
                + "shard_field, shard_start, shard_end, state) values (?,?,?,?,?,?,?,?,?,?,?)";
        Connection conn = null;
        PreparedStatement insert = null;
        try {
            conn = metldb.getJdbcTemplate().getDataSource().getConnection();
            insert = conn.prepareStatement(sql);
            insert.setString(1, dataTask.getString("create_user"));
            insert.setString(2, dataTask.getString("ocode"));
            insert.setString(3, dataTask.getString("source_obj"));
            insert.setString(4, dataTask.getString("target_obj"));
            insert.setString(5, null);
            insert.setString(6, sourceObj.getString("database"));
            insert.setString(7, sourceObj.getString("real_name"));
            insert.setString(8, sourceObj.getString("add_field"));
            insert.setString(9, start);
            insert.setString(10,end);
            //不需要审核
            if(Constants.WHETHER_FALSE.equals(dataTask.getString("is_examine"))){
                insert.setString(11,Constants.DATA_BILL_STATUS_EXAMINE_PASS);
            }else{
                insert.setString(11,Constants.DATA_BILL_STATUS_WAIT_EXAMINE);
            }
            insert.execute();
        } catch (SQLException e) {
            jee.logError("创建表的数据账单失败", e);
        } finally {
            Db.closeConn(jee, conn, insert);
        }
    }

    /**
     * @return dataTask 
     */
    public JSONObject getDataTask() {
        return dataTask;
    }

    /**
     * @param dataTask the dataTask to set
     */
    public void setDataTask(JSONObject dataTask) {
        this.dataTask = dataTask;
    }

    /**
     * @return sourceObj 
     */
    public JSONObject getSourceObj() {
        return sourceObj;
    }

    /**
     * @param sourceObj the sourceObj to set
     */
    public void setSourceObj(JSONObject sourceObj) {
        this.sourceObj = sourceObj;
    }

    /**
     * @return jee 
     */
    public JobEntryEval getJee() {
        return jee;
    }

    /**
     * @param jee the jee to set
     */
    public void setJee(JobEntryEval jee) {
        this.jee = jee;
    }
    
}
