/*
 * Copyright (c) 2016 Open Baton (http://www.openbaton.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.openbaton.vnfm.generic;

import com.google.gson.JsonObject;
import java.io.*;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import org.apache.commons.codec.binary.Base64;
import org.openbaton.catalogue.mano.common.Event;
import org.openbaton.catalogue.mano.descriptor.VNFComponent;
import org.openbaton.catalogue.mano.descriptor.VirtualDeploymentUnit;
import org.openbaton.catalogue.mano.record.VNFCInstance;
import org.openbaton.catalogue.mano.record.VNFRecordDependency;
import org.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.openbaton.catalogue.nfvo.Action;
import org.openbaton.catalogue.nfvo.ConfigurationParameter;
import org.openbaton.catalogue.nfvo.DependencyParameters;
import org.openbaton.catalogue.nfvo.Script;
import org.openbaton.catalogue.nfvo.VimInstance;
import org.openbaton.common.vnfm_sdk.AbstractVnfm;
import org.openbaton.common.vnfm_sdk.amqp.AbstractVnfmSpringAmqp;
import org.openbaton.common.vnfm_sdk.exception.BadFormatException;
import org.openbaton.common.vnfm_sdk.utils.VnfmUtils;
import org.openbaton.vnfm.generic.core.ElementManagementSystem;
import org.openbaton.vnfm.generic.core.LifeCycleManagement;
import org.openbaton.vnfm.generic.utils.JsonUtils;
import org.openbaton.vnfm.generic.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Created by mob on 16.07.15. */
@EnableScheduling
public class GenericVNFM extends AbstractVnfmSpringAmqp {

  @Autowired private LifeCycleManagement lcm;

  public static void main(String[] args) {
    SpringApplication.run(GenericVNFM.class, args);
  }

  private static String convertStreamToString(InputStream is) {
    Scanner s = new Scanner(is).useDelimiter("\\A");
    return s.hasNext() ? s.next() : "";
  }

  @Override
  public VirtualNetworkFunctionRecord instantiate(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      Object scripts,
      Map<String, Collection<VimInstance>> vimInstances)
      throws Exception {

    log.info(
        "Instantiation of VirtualNetworkFunctionRecord " + virtualNetworkFunctionRecord.getName());
//    for (VirtualDeploymentUnit virtualDeploymentUnit : virtualNetworkFunctionRecord.getVdu()) {
//      for (VNFCInstance vnfcInstance : virtualDeploymentUnit.getVnfc_instance()) {
//        ems.register(vnfcInstance.getHostname());
//      }
//    }
//    for (VirtualDeploymentUnit virtualDeploymentUnit : virtualNetworkFunctionRecord.getVdu()) {
//      for (VNFCInstance vnfcInstance : virtualDeploymentUnit.getVnfc_instance()) {
//        try {
//          ems.checkEms(vnfcInstance.getHostname());
//        } catch (BadFormatException e) {
//          throw new Exception(e.getMessage());
//        }
//      }
//    }
//    if (null != scripts) {
//      ems.saveScriptOnEms(virtualNetworkFunctionRecord, scripts);
//    }

    for (VirtualDeploymentUnit virtualDeploymentUnit : virtualNetworkFunctionRecord.getVdu()) {
      for (VNFCInstance vnfcInstance : virtualDeploymentUnit.getVnfc_instance()) {
        log.debug("VNFCInstance: " + vnfcInstance);
      }
    }

    String output = "\n--------------------\n--------------------\n";
    for (String result :
        lcm.executeScriptsForEvent(virtualNetworkFunctionRecord, Event.INSTANTIATE)) {
      output += JsonUtils.parse(result);
      output += "\n--------------------\n";
    }
    output += "\n--------------------\n";
    log.info("Executed script for INSTANTIATE. Output was: \n\n" + output);
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void query() {}

  @Override
  public VirtualNetworkFunctionRecord scale(
      Action scaleInOrOut,
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFComponent component,
      Object scripts,
      VNFRecordDependency dependency)
      throws Exception {
    VNFCInstance vnfcInstance = (VNFCInstance) component;
    if (scaleInOrOut.ordinal() == Action.SCALE_OUT.ordinal()) {
      log.info("Created VNFComponent");
      log.trace("HB_VERSION == " + virtualNetworkFunctionRecord.getHb_version());
      return virtualNetworkFunctionRecord;
    } else { // SCALE_IN

      String output = "\n--------------------\n--------------------\n";
      for (String result :
          lcm.executeScriptsForEventOnVnfr(
              virtualNetworkFunctionRecord, vnfcInstance, Event.SCALE_IN)) {
        output += JsonUtils.parse(result);
        output += "\n--------------------\n";
      }
      output += "\n--------------------\n";
      log.info("Executed script for SCALE_IN. Output was: \n\n" + output);

      return virtualNetworkFunctionRecord;
    }
  }

  @Override
  public void checkInstantiationFeasibility() {}

  @Override
  public VirtualNetworkFunctionRecord heal(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance component,
      String cause)
      throws Exception {

    if ("switchToStandby".equals(cause)) {
      for (VirtualDeploymentUnit virtualDeploymentUnit : virtualNetworkFunctionRecord.getVdu()) {
        for (VNFCInstance vnfcInstance : virtualDeploymentUnit.getVnfc_instance()) {
          if (vnfcInstance.getId().equals(component.getId())
              && "standby".equalsIgnoreCase(vnfcInstance.getState())) {
            log.debug("Activation of the standby component");
            if (VnfmUtils.getLifecycleEvent(
                    virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
                != null) {
              log.debug(
                  "Executed scripts for event START "
                      + lcm.executeScriptsForEvent(
                          virtualNetworkFunctionRecord, component, Event.START));
            }
            log.debug("Changing the status from standby to active");
            //This is inside the vnfr
            vnfcInstance.setState("ACTIVE");
            // This is a copy of the object received as parameter and modified.
            // It will be sent to the orchestrator
            component.setState("ACTIVE");
            break;
          }
        }
      }
    } else if (VnfmUtils.getLifecycleEvent(
            virtualNetworkFunctionRecord.getLifecycle_event(), Event.HEAL)
        != null) {
      if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.HEAL)
              .getLifecycle_events()
          != null) {
        log.debug("Heal method started");
        log.info("-----------------------------------------------------------------------");
        String output = "\n--------------------\n--------------------\n";
        for (String result :
            lcm.executeScriptsForEvent(
                virtualNetworkFunctionRecord, component, Event.HEAL, cause)) {
          output += JsonUtils.parse(result);
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info("Executed script for HEAL. Output was: \n\n" + output);
        log.info("-----------------------------------------------------------------------");
      }
    }
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord updateSoftware(
      Script script, VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    for (VirtualDeploymentUnit vdu : virtualNetworkFunctionRecord.getVdu()) {
      for (VNFCInstance vnfcInstance : vdu.getVnfc_instance()) {
        updateScript(script, virtualNetworkFunctionRecord, vnfcInstance);
      }
    }
    return virtualNetworkFunctionRecord;
  }

  private void updateScript(
      Script script,
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance vnfcInstance)
      throws Exception {

  }

  @Override
  public void upgradeSoftware() {}

  @Override
  public VirtualNetworkFunctionRecord modify(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFRecordDependency dependency)
      throws Exception {
    log.trace(
        "VirtualNetworkFunctionRecord VERSION is: " + virtualNetworkFunctionRecord.getHb_version());
    log.info("executing modify for VNFR: " + virtualNetworkFunctionRecord.getName());

    log.debug("Got dependency: " + dependency);
    log.debug("Parameters are: ");
    for (Entry<String, DependencyParameters> entry : dependency.getParameters().entrySet()) {
      log.debug("Source type: " + entry.getKey());
      log.debug("Parameters: " + entry.getValue().getParameters());
    }

    if (VnfmUtils.getLifecycleEvent(
            virtualNetworkFunctionRecord.getLifecycle_event(), Event.CONFIGURE)
        != null) {
      log.debug(
          "LifeCycle events: "
              + VnfmUtils.getLifecycleEvent(
                      virtualNetworkFunctionRecord.getLifecycle_event(), Event.CONFIGURE)
                  .getLifecycle_events());
      log.info("-----------------------------------------------------------------------");
      String output = "\n--------------------\n--------------------\n";
      for (String result :
          lcm.executeScriptsForEvent(virtualNetworkFunctionRecord, Event.CONFIGURE, dependency)) {
        output += JsonUtils.parse(result);
        output += "\n--------------------\n";
      }
      output += "\n--------------------\n";
      log.info("Executed script for CONFIGURE. Output was: \n\n" + output);
      log.info("-----------------------------------------------------------------------");
    } else {
      log.debug("No LifeCycle events for Event.CONFIGURE");
    }
    return virtualNetworkFunctionRecord;
  }

  //When the EMS reveive a script which terminate the vnf, the EMS is still running.
  //Once the vnf is terminated NFVO requests deletion of resources (MANO B.5) and the EMS will be terminated.
  @Override
  public VirtualNetworkFunctionRecord terminate(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    log.debug("Termination of VNF: " + virtualNetworkFunctionRecord.getName());
    if (VnfmUtils.getLifecycleEvent(
            virtualNetworkFunctionRecord.getLifecycle_event(), Event.TERMINATE)
        != null) {
      String output = "\n--------------------\n--------------------\n";
      for (String result :
          lcm.executeScriptsForEvent(virtualNetworkFunctionRecord, Event.TERMINATE)) {
        output += JsonUtils.parse(result);
        output += "\n--------------------\n";
      }
      output += "\n--------------------\n";
      log.info("Executed script for TERMINATE. Output was: \n\n" + output);
    }

    return virtualNetworkFunctionRecord;
  }

  @Override
  public void handleError(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) {
    log.error("Received Error for VNFR " + virtualNetworkFunctionRecord.getName());
    if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.ERROR)
        != null) {
      String output = "\n--------------------\n--------------------\n";
      try {
        for (String result :
            lcm.executeScriptsForEvent(virtualNetworkFunctionRecord, Event.ERROR)) {
          output += JsonUtils.parse(result);
          output += "\n--------------------\n";
        }
      } catch (Exception e) {
        e.printStackTrace();
        log.error("Exception executing Error handling");
      }
      output += "\n--------------------\n";
      log.info("Executed script for ERROR. Output was: \n\n" + output);
    }
  }

  @Override
  protected void fillSpecificProvides(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) {
    for (ConfigurationParameter configurationParameter :
        virtualNetworkFunctionRecord.getProvides().getConfigurationParameters()) {
      if (!configurationParameter.getConfKey().startsWith("#nfvo:")) {
        configurationParameter.setValue(String.valueOf((int) (Math.random() * 100)));
        log.debug(
            "Setting: "
                + configurationParameter.getConfKey()
                + " with value: "
                + configurationParameter.getValue());
      }
    }
  }

  @Override
  public VirtualNetworkFunctionRecord start(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {

    log.info("Starting vnfr: " + virtualNetworkFunctionRecord.getName());

    if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
        != null) {
      if (VnfmUtils.getLifecycleEvent(
                  virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
              .getLifecycle_events()
          != null) {
        String output = "\n--------------------\n--------------------\n";
        for (String result :
            lcm.executeScriptsForEvent(virtualNetworkFunctionRecord, Event.START)) {
          output += JsonUtils.parse(result);
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info("Executed script for START. Output was: \n\n" + output);
      }
    }
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord stop(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {

    log.info("Stopping vnfr: " + virtualNetworkFunctionRecord.getName());

    if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.STOP)
        != null) {
      if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.STOP)
              .getLifecycle_events()
          != null) {
        String output = "\n--------------------\n--------------------\n";
        for (String result : lcm.executeScriptsForEvent(virtualNetworkFunctionRecord, Event.STOP)) {
          output += JsonUtils.parse(result);
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info("Executed script for STOP. Output was: \n\n" + output);
      }
    }
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord startVNFCInstance(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFCInstance vnfcInstance)
      throws Exception {

    log.info("Starting vnfc instance: " + vnfcInstance.getHostname());

    if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
        != null) {
      if (VnfmUtils.getLifecycleEvent(
                  virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
              .getLifecycle_events()
          != null) {
        String output = "\n--------------------\n--------------------\n";
        for (String result :
            lcm.executeScriptsForEvent(virtualNetworkFunctionRecord, vnfcInstance, Event.START)) {
          output += JsonUtils.parse(result);
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info(
            "Executed script for START on VNFC Instance "
                + vnfcInstance.getHostname()
                + ". Output was: \n\n"
                + output);
      }
    }

    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord stopVNFCInstance(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFCInstance vnfcInstance)
      throws Exception {

    log.info("Stopping vnfc instance: " + vnfcInstance.getHostname());

    if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.STOP)
        != null) {
      if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.STOP)
              .getLifecycle_events()
          != null) {
        String output = "\n--------------------\n--------------------\n";
        for (String result :
            lcm.executeScriptsForEvent(virtualNetworkFunctionRecord, vnfcInstance, Event.STOP)) {
          output += JsonUtils.parse(result);
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info(
            "Executed script for STOP on VNFC Instance "
                + vnfcInstance.getHostname()
                + ". Output was: \n\n"
                + output);
      }
    }

    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord configure(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord resume(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance vnfcInstance,
      VNFRecordDependency dependency)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void NotifyChange() {}

  @Override
  protected void setup() {
    super.setup();
    String scriptPath = properties.getProperty("script-path", "/opt/openbaton/scripts");
    LogUtils.init();
  }

  @Override
  protected String getUserData() {
    String result = "";

    return result;
  }
}
