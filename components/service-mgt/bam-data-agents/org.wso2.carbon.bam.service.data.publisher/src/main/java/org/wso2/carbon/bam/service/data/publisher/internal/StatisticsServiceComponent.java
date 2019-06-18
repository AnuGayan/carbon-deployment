/*
* Copyright 2004,2005 The Apache Software Foundation.
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
*/
package org.wso2.carbon.bam.service.data.publisher.internal;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.wso2.carbon.bam.service.data.publisher.conf.EventConfigNStreamDef;
import org.wso2.carbon.bam.service.data.publisher.conf.EventPublisherConfig;
import org.wso2.carbon.bam.service.data.publisher.conf.RegistryPersistenceManager;
import org.wso2.carbon.bam.service.data.publisher.publish.ServiceAgentUtil;
import org.wso2.carbon.bam.service.data.publisher.services.ServiceDataPublisherAdmin;
import org.wso2.carbon.bam.service.data.publisher.util.CommonConstants;
import org.wso2.carbon.bam.service.data.publisher.util.ServiceStatisticsPublisherConstants;
import org.wso2.carbon.bam.service.data.publisher.util.TenantEventConfigData;
import org.wso2.carbon.base.ServerConfiguration;
import org.wso2.carbon.databridge.agent.exception.DataEndpointException;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.statistics.services.SystemStatisticsUtil;
import org.wso2.carbon.utils.Axis2ConfigurationContextObserver;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.ConfigurationContextService;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

@Component(
         name = "org.wso2.carbon.bam.service.data.publisher ", 
         immediate = true)
public class StatisticsServiceComponent {

    private static SystemStatisticsUtil systemStatisticsUtil;

    private static ConfigurationContext configurationContext;

    private static ServerConfiguration serverConfiguration;

    private static ServiceDataPublisherAdmin dataPublisherAdminService;

    private static boolean publishingEnabled;

    private static Log log = LogFactory.getLog(StatisticsServiceComponent.class);

    @Activate
    protected void activate(ComponentContext context) {
        try {
            dataPublisherAdminService = new ServiceDataPublisherAdmin();
            BundleContext bundleContext = context.getBundleContext();
            bundleContext.registerService(ServiceDataPublisherAdmin.class.getName(), dataPublisherAdminService, null);
        } catch (Throwable e) {
            // since component activation happen at startup log and ignore it.
            log.error("Error while registering service ", e);
        }
        checkPublishingEnabled();
        ServiceAgentUtil.setPublishingEnabled(publishingEnabled);
        // Engaging module only if  service publishing is enabled in bam.xml
        if (publishingEnabled) {
            try {
                // Engaging StatisticsModule as a global module
                configurationContext.getAxisConfiguration().engageModule(ServiceStatisticsPublisherConstants.BAM_SERVICE_STATISTICS_PUBLISHER_MODULE_NAME);
                BundleContext bundleContext = context.getBundleContext();
                bundleContext.registerService(Axis2ConfigurationContextObserver.class.getName(), new ServiceStatisticsAxis2ConfigurationContextObserver(), null);
                new RegistryPersistenceManager().load();
                log.info("BAM service statistics data publisher bundle is activated");
            } catch (AxisFault axisFault) {
                if (log.isErrorEnabled()) {
                    log.error("Failed to activate BAM service statistics data publisher bundle", axisFault);
                }
            } catch (Throwable t) {
                log.error("Failed to activate BAM service statistics data publisher bundle", t);
            }
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) throws DataEndpointException {
        if (log.isDebugEnabled()) {
            log.debug("BAM service statistics data publisher bundle is deactivated");
            Map<Integer, EventConfigNStreamDef> tenantSpecificEventConfig = TenantEventConfigData.getTenantSpecificEventingConfigData();
            for (Map.Entry<Integer, EventConfigNStreamDef> entry : tenantSpecificEventConfig.entrySet()) {
                EventConfigNStreamDef configData = entry.getValue();
                String key = configData.getUrl() + "_" + configData.getUserName() + "_" + configData.getPassword();
                EventPublisherConfig eventPublisherConfig = ServiceAgentUtil.getEventPublisherConfig(key);
                if (null != eventPublisherConfig) {
                    if (null != eventPublisherConfig.getDataPublisher()) {
                        eventPublisherConfig.getDataPublisher().shutdownWithAgent();
                    }
                }
            }
        }
    }

    private void checkPublishingEnabled() {
        OMElement bamConfig = getPublishingConfig();
        if (null != bamConfig) {
            OMElement servicePublishElement = bamConfig.getFirstChildWithName(new QName(CommonConstants.BAM_SERVICE_PUBLISH_OMELEMENT));
            if (null != servicePublishElement) {
                if (servicePublishElement.getText().trim().equalsIgnoreCase(CommonConstants.BAM_SERVICE_PUBLISH_ENABLED)) {
                    publishingEnabled = true;
                } else {
                    log.info("BAM Service Stat Publishing is disabled");
                    publishingEnabled = false;
                }
            } else {
                publishingEnabled = false;
            }
        } else {
            log.warn("Invalid " + CommonConstants.BAM_CONFIG_XML + ". Disabling service publishing.");
            publishingEnabled = false;
        }
    }

    private OMElement getPublishingConfig() {
        String bamConfigPath = CarbonUtils.getEtcCarbonConfigDirPath() + File.separator + CommonConstants.BAM_CONFIG_XML;
        File bamConfigFile = new File(bamConfigPath);
        try {
            XMLInputFactory xif = XMLInputFactory.newInstance();
            InputStream inputStream = new FileInputStream(bamConfigFile);
            XMLStreamReader reader = xif.createXMLStreamReader(inputStream);
            xif.setProperty("javax.xml.stream.isCoalescing", false);
            StAXOMBuilder builder = new StAXOMBuilder(reader);
            return builder.getDocument().getOMDocumentElement();
        } catch (FileNotFoundException e) {
            log.warn("No " + CommonConstants.BAM_CONFIG_XML + " found in " + bamConfigPath);
            return null;
        } catch (XMLStreamException e) {
            log.error("Incorrect format " + CommonConstants.BAM_CONFIG_XML + " file", e);
            return null;
        }
    }

    @Reference(
             name = "org.wso2.carbon.statistics.services", 
             service = org.wso2.carbon.statistics.services.SystemStatisticsUtil.class, 
             cardinality = ReferenceCardinality.MANDATORY, 
             policy = ReferencePolicy.DYNAMIC, 
             unbind = "unsetSystemStatisticsUtil")
    protected void setSystemStatisticsUtil(SystemStatisticsUtil systemStatisticsUtil) {
        this.systemStatisticsUtil = systemStatisticsUtil;
    }

    public static SystemStatisticsUtil getSystemStatisticsUtil() {
        return systemStatisticsUtil;
    }

    protected void unsetSystemStatisticsUtil(SystemStatisticsUtil sysStatUtil) {
        systemStatisticsUtil = null;
    }

    @Reference(
             name = "config.context.service", 
             service = org.wso2.carbon.utils.ConfigurationContextService.class, 
             cardinality = ReferenceCardinality.MANDATORY, 
             policy = ReferencePolicy.DYNAMIC, 
             unbind = "unsetConfigurationContextService")
    protected void setConfigurationContextService(ConfigurationContextService configurationContextService) {
        configurationContext = configurationContextService.getServerConfigContext();
    }

    protected void unsetConfigurationContextService(ConfigurationContextService configurationContextService) {
        configurationContext = null;
    }

    @Reference(
             name = "org.wso2.carbon.registry.service", 
             service = org.wso2.carbon.registry.core.service.RegistryService.class, 
             cardinality = ReferenceCardinality.MANDATORY, 
             policy = ReferencePolicy.DYNAMIC, 
             unbind = "unsetRegistryService")
    protected void setRegistryService(RegistryService registryService) {
        try {
            RegistryPersistenceManager.setRegistryService(registryService);
        } catch (Exception e) {
            log.error("Cannot retrieve System Registry", e);
        }
    }

    protected void unsetRegistryService(RegistryService registryService) {
        RegistryPersistenceManager.setRegistryService(null);
    }

    public static boolean isPublishingEnabled() {
        return publishingEnabled;
    }
}

