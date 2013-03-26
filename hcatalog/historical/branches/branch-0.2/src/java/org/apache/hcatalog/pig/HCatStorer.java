/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hcatalog.pig;

import java.io.IOException;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hcatalog.common.HCatConstants;
import org.apache.hcatalog.common.HCatException;
import org.apache.hcatalog.common.HCatUtil;
import org.apache.hcatalog.data.schema.HCatSchema;
import org.apache.hcatalog.mapreduce.HCatOutputCommitter;
import org.apache.hcatalog.mapreduce.HCatOutputFormat;
import org.apache.hcatalog.mapreduce.HCatTableInfo;
import org.apache.pig.PigException;
import org.apache.pig.ResourceSchema;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.util.ObjectSerializer;
import org.apache.pig.impl.util.UDFContext;

/**
 * HCatStorer.
 *
 */

public class HCatStorer extends HCatBaseStorer {

  /**
   *
   */

  public HCatStorer(String partSpecs, String schema) throws Exception {
    super(partSpecs, schema);
  }

  public HCatStorer(String partSpecs) throws Exception {
    this(partSpecs, null);
  }

  public HCatStorer() throws Exception{
    this(null,null);
  }

  @Override
  public OutputFormat getOutputFormat() throws IOException {
    return new HCatOutputFormat();
  }

  @Override
  public void setStoreLocation(String location, Job job) throws IOException {

    Properties p = UDFContext.getUDFContext().getUDFProperties(this.getClass(), new String[]{sign});

    String[] userStr = location.split("\\.");
    HCatTableInfo tblInfo;
    if(userStr.length == 2) {
      tblInfo = HCatTableInfo.getOutputTableInfo(PigHCatUtil.getHCatServerUri(job),
          PigHCatUtil.getHCatServerPrincipal(job), userStr[0],userStr[1],partitions);
    } else {
      tblInfo = HCatTableInfo.getOutputTableInfo(PigHCatUtil.getHCatServerUri(job),
          PigHCatUtil.getHCatServerPrincipal(job), null,userStr[0],partitions);
    }



    Configuration config = job.getConfiguration();
    if(!HCatUtil.checkJobContextIfRunningFromBackend(job)){

      Schema schema = (Schema)ObjectSerializer.deserialize(p.getProperty(PIG_SCHEMA));
      if(schema != null){
        pigSchema = schema;
      }
      if(pigSchema == null){
        throw new FrontendException("Schema for data cannot be determined.", PigHCatUtil.PIG_EXCEPTION_CODE);
      }
      try{
        HCatOutputFormat.setOutput(job, tblInfo);
      } catch(HCatException he) {
          // pass the message to the user - essentially something about the table
          // information passed to HCatOutputFormat was not right
          throw new PigException(he.getMessage(), PigHCatUtil.PIG_EXCEPTION_CODE, he);
      }
      HCatSchema hcatTblSchema = HCatOutputFormat.getTableSchema(job);
      try{
        doSchemaValidations(pigSchema, hcatTblSchema);
      } catch(HCatException he){
        throw new FrontendException(he.getMessage(), PigHCatUtil.PIG_EXCEPTION_CODE, he);
      }
      computedSchema = convertPigSchemaToHCatSchema(pigSchema,hcatTblSchema);
      HCatOutputFormat.setSchema(job, computedSchema);
      p.setProperty(HCatConstants.HCAT_KEY_OUTPUT_INFO, config.get(HCatConstants.HCAT_KEY_OUTPUT_INFO));
      
      PigHCatUtil.saveConfigIntoUDFProperties(p, config,HCatConstants.HCAT_KEY_HIVE_CONF);
      PigHCatUtil.saveConfigIntoUDFProperties(p, config,HCatConstants.HCAT_DYNAMIC_PTN_JOBID);
      PigHCatUtil.saveConfigIntoUDFProperties(p, config,HCatConstants.HCAT_KEY_TOKEN_SIGNATURE);
      PigHCatUtil.saveConfigIntoUDFProperties(p, config,HCatConstants.HCAT_KEY_JOBCLIENT_TOKEN_SIGNATURE);
      PigHCatUtil.saveConfigIntoUDFProperties(p, config,HCatConstants.HCAT_KEY_JOBCLIENT_TOKEN_STRFORM);
      
      p.setProperty(COMPUTED_OUTPUT_SCHEMA,ObjectSerializer.serialize(computedSchema));

    }else{
      config.set(HCatConstants.HCAT_KEY_OUTPUT_INFO, p.getProperty(HCatConstants.HCAT_KEY_OUTPUT_INFO));
      
      PigHCatUtil.getConfigFromUDFProperties(p, config, HCatConstants.HCAT_KEY_HIVE_CONF);
      PigHCatUtil.getConfigFromUDFProperties(p, config, HCatConstants.HCAT_DYNAMIC_PTN_JOBID);
      PigHCatUtil.getConfigFromUDFProperties(p, config, HCatConstants.HCAT_KEY_TOKEN_SIGNATURE);
      PigHCatUtil.getConfigFromUDFProperties(p, config, HCatConstants.HCAT_KEY_JOBCLIENT_TOKEN_SIGNATURE);
      PigHCatUtil.getConfigFromUDFProperties(p, config, HCatConstants.HCAT_KEY_JOBCLIENT_TOKEN_STRFORM);
      
    }
  }


  @Override
  public void storeSchema(ResourceSchema schema, String arg1, Job job) throws IOException {
    if( job.getConfiguration().get("mapred.job.tracker", "").equalsIgnoreCase("local") ) {
      //In local mode, mapreduce will not call HCatOutputCommitter.cleanupJob.
      //Calling it from here so that the partition publish happens.
      //This call needs to be removed after MAPREDUCE-1447 is fixed.
      new HCatOutputCommitter(job,null).cleanupJob(job);
    }
  }
}