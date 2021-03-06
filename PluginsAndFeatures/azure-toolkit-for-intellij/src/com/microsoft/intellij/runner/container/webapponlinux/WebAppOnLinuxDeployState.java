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

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.microsoft.azure.management.appservice.WebApp;
import com.microsoft.azuretools.core.mvp.model.webapp.AzureWebAppMvpModel;
import com.microsoft.azuretools.core.mvp.model.webapp.PrivateRegistryImageSetting;
import com.microsoft.azuretools.core.mvp.model.webapp.WebAppOnLinuxDeployModel;
import com.microsoft.azuretools.telemetry.AppInsightsClient;
import com.microsoft.intellij.container.Constant;
import com.microsoft.intellij.container.utils.DockerUtil;
import com.microsoft.intellij.runner.RunProcessHandler;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.ProgressHandler;
import com.spotify.docker.client.exceptions.DockerException;

import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.FileNotFoundException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import rx.Observable;
import rx.schedulers.Schedulers;

public class WebAppOnLinuxDeployState implements RunProfileState {
    private static final String DOCKER_CONTEXT_FOLDER_NAME = "dockerContext";
    private static final String DOCKER_FILE_NAME = "Dockerfile";

    private final WebAppOnLinuxDeployModel deployModel;
    private final Project project;

    private final RunProcessHandler processHandler = new RunProcessHandler();

    public WebAppOnLinuxDeployState(Project project, WebAppOnLinuxDeployModel webAppOnLinuxDeployModel) {
        this.deployModel = webAppOnLinuxDeployModel;
        this.project = project;
    }

    @Override
    public ExecutionResult execute(Executor executor, @NotNull ProgramRunner programRunner) throws ExecutionException {
        ConsoleView consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(this.project).getConsole();
        processHandler.startNotify();
        consoleView.attachToProcess(processHandler);

        Observable.fromCallable(
                () -> {
                    println("Starting job ...  ");
                    String basePath = project.getBasePath();
                    if (basePath == null) {
                        errorln("Project base path is null.");
                        throw new FileNotFoundException("Project base path is null.");
                    }
                    // locate war file to specified location
                    println("Locate war file ...  ");
                    String targetFilePath = deployModel.getTargetPath();
                    String targetBuildPath = Paths.get(targetFilePath).getParent().toString();
                    String targetFileName = deployModel.getTargetName();

                    FileUtils.copyFile(
                            Paths.get(targetBuildPath, targetFileName).toFile(),
                            Paths.get(targetBuildPath, DOCKER_CONTEXT_FOLDER_NAME, targetFileName).toFile()
                    );
                    // build image
                    println("Build image ...  ");
                    String dockerContent = String.format(Constant.DOCKERFILE_CONTENT_TOMCAT, project.getName());
                    DockerUtil.createDockerFile(project, DOCKER_CONTEXT_FOLDER_NAME, DOCKER_FILE_NAME, dockerContent);
                    FileUtils.copyDirectory(
                            Paths.get(basePath, DOCKER_CONTEXT_FOLDER_NAME).toFile(),
                            Paths.get(targetBuildPath, DOCKER_CONTEXT_FOLDER_NAME).toFile()
                    );
                    DockerClient docker = DefaultDockerClient.fromEnv().build();
                    String latestImageName = DockerUtil.buildImage(docker, project, Paths.get(targetBuildPath,
                            DOCKER_CONTEXT_FOLDER_NAME));

                    // push to ACR
                    println("Push to ACR ...  ");
                    ProgressHandler progressHandler = message -> {
                        // TODO: progress output
                        println(String.format("%s%s",
                                message.id() != null ? message.id() + "\t" : "",
                                message.status() != null ? message.status() : ""));
                        if (message.error() != null) {
                            throw new DockerException(message.toString());
                        }
                    };
                    PrivateRegistryImageSetting acrInfo = deployModel.getPrivateRegistryImageSetting();
                    DockerUtil.pushImage(docker, acrInfo.getServerUrl(), acrInfo.getUsername(), acrInfo.getPassword(),
                            latestImageName, acrInfo.getImageNameWithTag(), progressHandler);

                    // deploy
                    if (deployModel.isCreatingNewWebAppOnLinux()) {
                        // create new WebApp
                        println("Create new WebApp ...  ");
                        WebApp app = AzureWebAppMvpModel.getInstance()
                                .createWebAppOnLinux(deployModel.getSubscriptionId(), deployModel,
                                        deployModel.getPrivateRegistryImageSetting());

                        if (app != null && app.name() != null) {
                            println(String.format("URL:  http://%s.azurewebsites.net/%s", app.name(),
                                    project.getName()));
                            updateConfigurationDataModel(app);
                        }
                    } else {
                        // update WebApp
                        println("Update WebApp ...  ");
                        WebApp app = AzureWebAppMvpModel.getInstance()
                                .updateWebAppOnLinux(deployModel.getSubscriptionId(), deployModel.getWebAppId(),
                                        acrInfo);
                        if (app != null && app.name() != null) {
                            println(String.format("URL:  http://%s.azurewebsites.net/%s", app.name(),
                                    project.getName()));
                        }
                    }
                    return null;
                }
        ).subscribeOn(Schedulers.io()).subscribe(
                // TODO: use getSchedulerProvider, but currently defined in MvpPresenter
                (res) -> {
                    println("Update cache ... ");
                    AzureWebAppMvpModel.getInstance().listAllWebAppsOnLinux(true);
                    println("Job done");
                    processHandler.notifyProcessTerminated(0);
                    sendTelemetry(true);
                },
                (err) -> {
                    err.printStackTrace();
                    errorln(err.getMessage());
                    processHandler.notifyProcessTerminated(0);
                    sendTelemetry(false);
                }
        );
        return new DefaultExecutionResult(consoleView, processHandler);
    }

    // TODO: refactor later
    private void sendTelemetry(boolean success) {
        Map<String, String> map = new HashMap<String, String>();
        map.put("SubscriptionId", deployModel.getSubscriptionId());
        map.put("CreateNewApp", String.valueOf(deployModel.isCreatingNewWebAppOnLinux()));
        map.put("CreateNewSP", String.valueOf(deployModel.isCreatingNewAppServicePlan()));
        map.put("CreateNewRGP", String.valueOf(deployModel.isCreatingNewResourceGroup()));
        map.put("Success", String.valueOf(success));

        AppInsightsClient.createByType(AppInsightsClient.EventType.WebApp, "Webapp (Linux)", "Deploy", map);
    }

    private void updateConfigurationDataModel(WebApp app) {
        deployModel.setCreatingNewWebAppOnLinux(false);
        deployModel.setWebAppId(app.id());
        deployModel.setResourceGroupName(app.resourceGroupName());
    }

    private void println(String message, Key type) {
        if (!processHandler.isProcessTerminating() && !processHandler.isProcessTerminated()) {
            processHandler.notifyTextAvailable(message + "\n", type);
        } else {
            throw new Error("The process has been terminated");
        }
    }

    private void println(String message) {
        println(message, ProcessOutputTypes.SYSTEM);
    }

    private void errorln(String message) {
        println(message, ProcessOutputTypes.STDERR);
    }
}
