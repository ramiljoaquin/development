/*******************************************************************************
 *                                                                              
 *  Copyright FUJITSU LIMITED 2016                                             
 *                                                                              
 *******************************************************************************/

package org.oscm.webtest.setup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.oscm.converter.PropertiesLoader;
import org.oscm.internal.intf.TenantService;
import org.oscm.internal.types.enumtypes.IdpSettingType;
import org.oscm.internal.vo.VOTenant;
import org.oscm.internal.vo.VOTenantSetting;

public class TenantCreateTask extends WebtestTask {

    private String path;
    private String tenantId;
    private String name;
    private String fileName;

    @Override
    public void executeInternal() throws Exception {
        TenantService tenantSvc = getServiceInterface(TenantService.class);
        VOTenant voTenant = new VOTenant();
        voTenant.setTenantId(tenantId);
        voTenant.setName(name);
        voTenant.setDescription("");

        List<VOTenantSetting> tenantSettings = new ArrayList<>();
        Map<IdpSettingType, String> settingsMap = new HashMap<>();

        Properties properties = readPropertiesFromFile();

        for (Object propertyKey : properties.keySet()) {
            String key = (String) propertyKey;
            if (!IdpSettingType.contains(key)) {
                continue;
            }
            String value = properties.getProperty(key);
            VOTenantSetting voTenantSetting = new VOTenantSetting();
            voTenantSetting.setName(IdpSettingType.valueOf(key));
            voTenantSetting.setValue(value);
            voTenantSetting.setVoTenant(voTenant);
            tenantSettings.add(voTenantSetting);

            settingsMap.put(IdpSettingType.valueOf(key), value);
        }

        if (tenantSettings.isEmpty()) {
            handleErrorOutput(
                    "Problem while creating tenant settings from file '"
                            + getFilePath() + "'");
        }

        voTenant.setTenantSettings(settingsMap);

        tenantSvc.addTenant(voTenant);
        tenantSvc.addTenantSettings(tenantSettings, voTenant);
    }

    protected Properties readPropertiesFromFile() throws FileNotFoundException {
        File file = new File(getFilePath());
        Properties propsToStore = null;
        try (InputStream inputStream = new FileInputStream(file)) {
            propsToStore = PropertiesLoader.loadProperties(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return propsToStore;
    }

    private String getFilePath() {
        return path + fileName;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
