/*
 * Copyright 2016 Herman Cheung
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package hkhc.electricspock.internal;

import android.app.Application;
import android.os.Build;

import org.jetbrains.annotations.NotNull;
import org.robolectric.DefaultTestLifecycle;
import org.robolectric.TestLifecycle;
import org.robolectric.annotation.Config;
import org.robolectric.internal.ParallelUniverseInterface;
import org.robolectric.internal.SdkConfig;
import org.robolectric.internal.SdkEnvironment;
import org.robolectric.internal.dependency.DependencyResolver;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.PackageResourceTable;
import org.robolectric.res.ResourceMerger;
import org.robolectric.res.ResourcePath;
import org.robolectric.res.ResourceTableFactory;
import org.robolectric.res.RoutingResourceTable;
import org.robolectric.util.ReflectionHelpers;
import org.spockframework.runtime.extension.AbstractMethodInterceptor;
import org.spockframework.runtime.extension.IMethodInvocation;
import org.spockframework.runtime.model.SpecInfo;

import java.util.HashMap;
import java.util.Map;

import hkhc.electricspock.ElectricSputnik;

/**
 * Created by herman on 27/12/2016.
 */

public class ElectricSpockInterceptor extends AbstractMethodInterceptor {

    private SpecInfo spec;
    private SdkEnvironment sdkEnvironment;
    private Config config;
    private AndroidManifest appManifest;
    private TestLifecycle<Application> testLifecycle;
//    private DependencyResolverFactory dependencyResolverFactory;
    private DependencyResolver dependencyResolver;
    private static final Map<AndroidManifest, PackageResourceTable> appResourceTableCache = new HashMap<>();
    private static PackageResourceTable compiletimeSdkResourceTable;

    public ElectricSpockInterceptor(SpecInfo spec, SdkEnvironment sdk, Config config, AndroidManifest appManifest,
                                    DependencyResolver dependencyResolver) {
        this.spec = spec;
        this.sdkEnvironment = sdk;
        this.config = config;
        this.appManifest = appManifest;
        this.dependencyResolver = dependencyResolver;
//        this.dependencyResolverFactory = dependencyResolverFactory;

        spec.addInterceptor(this);
    }

    @SuppressWarnings("unchecked")
    private void assureTestLifecycle() {
        try {
            ClassLoader robolectricClassLoader = sdkEnvironment.getRobolectricClassLoader();
            testLifecycle = (TestLifecycle) robolectricClassLoader.loadClass(getTestLifecycleClass().getName()).newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    protected Class<? extends TestLifecycle> getTestLifecycleClass() {
        return DefaultTestLifecycle.class;
    }

    private ParallelUniverseInterface getHooksInterface(SdkEnvironment sdkEnvironment) {
        try {
            @SuppressWarnings("unchecked")
            Class<ParallelUniverseInterface> aClass = (Class<ParallelUniverseInterface>)
                    sdkEnvironment.getRobolectricClassLoader().loadClass(ElectricParallelUniverse.class.getName());

            return aClass.newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /*
    Migrate from RobolectricTestRunner.methodBlock
     */
    @Override
    public void interceptSpecExecution(IMethodInvocation invocation) throws Throwable {

        Thread.currentThread().setContextClassLoader(sdkEnvironment.getRobolectricClassLoader());

        ParallelUniverseInterface parallelUniverseInterface = getHooksInterface(sdkEnvironment);
        try {
            // Only invoke @BeforeClass once per class
            assureTestLifecycle();

            parallelUniverseInterface.resetStaticState(config);
            parallelUniverseInterface.setSdkConfig(sdkEnvironment.getSdkConfig());

            int sdkVersion = (new AndroidManifestFactory()).pickSdkVersion(config, appManifest);
            ReflectionHelpers.setStaticField(sdkEnvironment.bootstrappedClass(Build.VERSION.class),
                    "SDK_INT", sdkVersion);
            SdkConfig sdkConfig = new SdkConfig(sdkVersion);
            ReflectionHelpers.setStaticField(sdkEnvironment.bootstrappedClass(Build.VERSION.class),
                    "RELEASE", sdkConfig.getAndroidVersion());

            PackageResourceTable systemResourceTable = sdkEnvironment.getSystemResourceTable(dependencyResolver);
            PackageResourceTable appResourceTable = getAppResourceTable(appManifest);

            parallelUniverseInterface.setUpApplicationState(null, testLifecycle, appManifest, config,
                    new RoutingResourceTable(getCompiletimeSdkResourceTable(), appResourceTable),
                    new RoutingResourceTable(systemResourceTable, appResourceTable),
                    new RoutingResourceTable(systemResourceTable));

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        // todo: this try/finally probably isn't right -- should mimic RunAfters? [xw]
        try {
            invocation.proceed();
        } finally {
            try {
                parallelUniverseInterface.tearDownApplication();
                parallelUniverseInterface.resetStaticState(config); // afterward too, so stuff doesn't hold on to classes?
            } finally {
                Thread.currentThread().setContextClassLoader(ElectricSputnik.class.getClassLoader());
            }
        }

    }

    private final PackageResourceTable getAppResourceTable(final AndroidManifest appManifest) {
        PackageResourceTable resourceTable = appResourceTableCache.get(appManifest);
        if (resourceTable == null) {
            resourceTable = ResourceMerger.buildResourceTable(appManifest);

            appResourceTableCache.put(appManifest, resourceTable);
        }
        return resourceTable;
    }

    /**
     * Returns the ResourceProvider for the compile time SDK.
     */
    @NotNull
    private static PackageResourceTable getCompiletimeSdkResourceTable() {
        if (compiletimeSdkResourceTable == null) {
            compiletimeSdkResourceTable = ResourceTableFactory.newResourceTable("android",
                    new ResourcePath(android.R.class, null, null));
        }
        return compiletimeSdkResourceTable;
    }


}