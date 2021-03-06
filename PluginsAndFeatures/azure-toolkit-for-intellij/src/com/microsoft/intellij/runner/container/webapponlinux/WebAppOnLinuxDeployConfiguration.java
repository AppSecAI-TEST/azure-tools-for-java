/*
 * Copyright (c) Microsoft Corporation
 *
 * All rights reserved.
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.microsoft.intellij.runner.container.webapponlinux;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.util.xmlb.XmlSerializer;
import com.microsoft.azuretools.azurecommons.util.Utils;
import com.microsoft.azuretools.core.mvp.model.webapp.PrivateRegistryImageSetting;
import com.microsoft.azuretools.core.mvp.model.webapp.WebAppOnLinuxDeployModel;

import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class WebAppOnLinuxDeployConfiguration extends RunConfigurationBase {

    private static final String MISSING_SERVER_URL = "Please specify Server URL.";
    private static final String MISSING_USERNAME = "Please specify Username.";
    private static final String MISSING_PASSWORD = "Please specify Password.";
    private static final String MISSING_IMAGE_WITH_TAG = "Please specify Image and Tag.";
    private static final String MISSING_WEB_APP = "Please specify Web App on Linux.";
    private static final String MISSING_SUBSCRIPTION = "Please specify Subscription.";
    private static final String MISSING_RESOURCE_GROUP = "Please specify Resource Group.";
    private static final String MISSING_APP_SERVICE_PLAN = "Please specify App Service Plan.";

    // TODO: move to util
    private static final String MISSING_ARTIFACT = "A web archive (.war) artifact has not been configured.";
    private static final String INVALID_WAR_FILE = "The artifact name %s is invalid. "
            + "An artifact name maycontain only the ASCII letters 'a' through 'z' (case-insensitive), "
            + "and the digits '0' through '9',  '-' and '_'.";
    private static final String WAR_NAME_REGEX = "^[A-Za-z0-9_-]+\\.war$";

    private final WebAppOnLinuxDeployModel deployModel;
    private boolean firstTimeCreated = true;

    protected WebAppOnLinuxDeployConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory,
                                               String name) {
        super(project, factory, name);
        deployModel = new WebAppOnLinuxDeployModel();
    }

    public boolean isFirstTimeCreated() {
        return firstTimeCreated;
    }

    public void setFirstTimeCreated(boolean firstTimeCreated) {
        this.firstTimeCreated = firstTimeCreated;
    }

    public WebAppOnLinuxDeployModel getDeployModel() {
        return deployModel;
    }

    @NotNull
    @Override
    public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new WebAppOnLinuxDeploySettingsEditor(this.getProject());
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
    }

    @Override
    public void readExternal(Element element) throws InvalidDataException {
        super.readExternal(element);
        firstTimeCreated = Comparing.equal(element.getAttributeValue("default"), "true");
        XmlSerializer.deserializeInto(deployModel, element);
    }

    @Override
    public void writeExternal(Element element) throws WriteExternalException {
        super.writeExternal(element);
        XmlSerializer.serializeInto(deployModel, element);
    }

    @Nullable
    @Override
    public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment)
            throws ExecutionException {
        return new WebAppOnLinuxDeployState(getProject(), deployModel);
    }

    public void validate() throws ConfigurationException {
        // acr
        if (Utils.isEmptyString(deployModel.getPrivateRegistryImageSetting().getServerUrl())) {
            throw new ConfigurationException(MISSING_SERVER_URL);
        }
        if (Utils.isEmptyString(deployModel.getPrivateRegistryImageSetting().getUsername())) {
            throw new ConfigurationException(MISSING_USERNAME);
        }
        if (Utils.isEmptyString(deployModel.getPrivateRegistryImageSetting().getPassword())) {
            throw new ConfigurationException(MISSING_PASSWORD);
        }
        if (Utils.isEmptyString(deployModel.getPrivateRegistryImageSetting().getImageNameWithTag())) {
            throw new ConfigurationException(MISSING_IMAGE_WITH_TAG);
        }
        // web app
        if (deployModel.isCreatingNewWebAppOnLinux()) {
            if (Utils.isEmptyString(deployModel.getWebAppName())) {
                throw new ConfigurationException(MISSING_WEB_APP);
            }
            if (Utils.isEmptyString(deployModel.getSubscriptionId())) {
                throw new ConfigurationException(MISSING_SUBSCRIPTION);
            }
            if (Utils.isEmptyString(deployModel.getResourceGroupName())) {
                throw new ConfigurationException(MISSING_RESOURCE_GROUP);
            }

            if (deployModel.isCreatingNewAppServicePlan()) {
                if (Utils.isEmptyString(deployModel.getAppServicePlanName())) {
                    throw new ConfigurationException(MISSING_APP_SERVICE_PLAN);
                }
            } else {
                if (Utils.isEmptyString(deployModel.getAppServicePlanId())) {
                    throw new ConfigurationException(MISSING_APP_SERVICE_PLAN);
                }
            }

        } else {
            if (Utils.isEmptyString(deployModel.getWebAppId())) {
                throw new ConfigurationException(MISSING_WEB_APP);
            }
        }

        // target package
        if (deployModel == null || Utils.isEmptyString(deployModel.getTargetName())) {
            throw new ConfigurationException(MISSING_ARTIFACT);
        }
        if (!deployModel.getTargetName().matches(WAR_NAME_REGEX)) {
            throw new ConfigurationException(String.format(INVALID_WAR_FILE, deployModel.getTargetName()));
        }
    }

    public String getAppName() {
        return deployModel.getWebAppName();
    }

    public void setAppName(String appName) {
        deployModel.setWebAppName(appName);
    }

    public String getSubscriptionId() {
        return deployModel.getSubscriptionId();
    }

    public void setSubscriptionId(String subscriptionId) {
        deployModel.setSubscriptionId(subscriptionId);
    }

    public boolean isCreatingNewResourceGroup() {
        return deployModel.isCreatingNewResourceGroup();
    }

    public void setCreatingNewResourceGroup(boolean creatingNewResourceGroup) {
        deployModel.setCreatingNewResourceGroup(creatingNewResourceGroup);
    }

    public String getResourceGroupName() {
        return deployModel.getResourceGroupName();
    }

    public void setResourceGroupName(String resourceGroupName) {
        deployModel.setResourceGroupName(resourceGroupName);
    }

    public String getLocationName() {
        return deployModel.getLocationName();
    }

    public void setLocationName(String locationName) {
        deployModel.setLocationName(locationName);
    }

    public String getPricingSkuTier() {
        return deployModel.getPricingSkuTier();
    }

    public void setPricingSkuTier(String pricingSkuTier) {
        deployModel.setPricingSkuTier(pricingSkuTier);
    }

    public String getPricingSkuSize() {
        return deployModel.getPricingSkuSize();
    }

    public void setPricingSkuSize(String pricingSkuSize) {
        deployModel.setPricingSkuSize(pricingSkuSize);
    }

    public PrivateRegistryImageSetting getPrivateRegistryImageSetting() {
        return deployModel.getPrivateRegistryImageSetting();
    }

    public void setPrivateRegistryImageSetting(PrivateRegistryImageSetting privateRegistryImageSetting) {
        deployModel.setPrivateRegistryImageSetting(privateRegistryImageSetting);
    }

    public String getWebAppId() {
        return deployModel.getWebAppId();
    }

    public void setWebAppId(String webAppId) {
        deployModel.setWebAppId(webAppId);
    }

    public boolean isCreatingNewWebAppOnLinux() {
        return deployModel.isCreatingNewWebAppOnLinux();
    }

    public void setCreatingNewWebAppOnLinux(boolean creatingNewWebAppOnLinux) {
        deployModel.setCreatingNewWebAppOnLinux(creatingNewWebAppOnLinux);
    }

    public boolean isCreatingNewAppServicePlan() {
        return deployModel.isCreatingNewAppServicePlan();
    }

    public void setCreatingNewAppServicePlan(boolean creatingNewAppServicePlan) {
        deployModel.setCreatingNewAppServicePlan(creatingNewAppServicePlan);
    }

    public String getAppServicePlanId() {
        return deployModel.getAppServicePlanId();
    }

    public void setAppServicePlanId(String appServicePlanId) {
        deployModel.setAppServicePlanId(appServicePlanId);
    }

    public String getAppServicePlanName() {
        return deployModel.getAppServicePlanName();
    }

    public void setAppServicePlanName(String appServicePlanName) {
        deployModel.setAppServicePlanName(appServicePlanName);
    }

    public void setTargetPath(String targetPath) {
        deployModel.setTargetPath(targetPath);
    }

    public String getTargetPath() {
        return deployModel.getTargetPath();
    }

    public void setTargetName(String targetName) {
        deployModel.setTargetName(targetName);
    }

    public String getTargetName() {
        return deployModel.getTargetName();
    }
}
