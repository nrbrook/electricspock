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

package hkhc.electricspock;

import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.manipulation.Sortable;
import org.junit.runner.manipulation.Sorter;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.junit.platform.runner.JUnitPlatform;
import org.robolectric.annotation.Config;
import org.robolectric.internal.AndroidSandbox;
import org.spockframework.runtime.model.SpecInfo;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import hkhc.electricspock.internal.ContainedRobolectricTestRunner;
import hkhc.electricspock.internal.ElectricSpockInterceptor;
import spock.lang.Specification;
import spock.lang.Title;

/**
 * Created by herman on 27/12/2016.
 * Test Runner
 */

public class ElectricSputnik extends Runner implements Filterable, Sortable {

    private final AndroidSandbox sdkEnvironment;

    /* it is used to setup Robolectric infrastructure, and not used to run actual test cases */
    private final ContainedRobolectricTestRunner containedRunner;

    /* Used to check if object of proper class is obtained from getSpec method */
    private final Class<Object> specInfoClass;

    /* the real test runner to run test classes. It is enclosed by ElectricSputnik so that it is
    run within Robolectric interception
     */
    private final Runner sputnik;

    static {
        new SecureRandom(); // this starts up the Poller SunPKCS11-Darwin thread early, outside of any Robolectric classloader
    }

    public ElectricSputnik(Class<? extends Specification> specClass) throws InitializationError {

        /* The project is so sensitive to the version of Robolectric, that we strictly check
        its version before proceed
         */
        (new RobolectricVersionChecker()).checkRobolectricVersion();

        containedRunner = new ContainedRobolectricTestRunner(specClass.getAnnotation(Config.class));
        sdkEnvironment = containedRunner.getContainedSdkEnvironment();


        specInfoClass = sdkEnvironment.bootstrappedClass(SpecInfo.class);

        // Since we have bootstrappedClass we may properly initialize
        sputnik = createSputnik(specClass);

        registerSpec();

    }

    private static final String ANNOTATION_METHOD = "annotationData";
    private static final String ANNOTATIONS = "annotations";


    @SuppressWarnings("unchecked")
    public static void alterAnnotationValueJDK8(Class<?> targetClass, Class<? extends Annotation> targetAnnotation, Annotation targetValue) {
        try {
            Method method = Class.class.getDeclaredMethod(ANNOTATION_METHOD);
            method.setAccessible(true);

            Object annotationData = method.invoke(targetClass);

            if (annotationData != null) {
                Field annotations = annotationData.getClass().getDeclaredField(ANNOTATIONS);
                annotations.setAccessible(true);

                Map<Class<? extends Annotation>, Annotation> map = (Map<Class<? extends Annotation>, Annotation>) annotations.get(annotationData);
                if (map != null) {
                    map.put(targetAnnotation, targetValue);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    static final RunWith JUnitPlatformRunWithAnnotation = new RunWith() {
        @Override
        public Class<? extends Annotation> annotationType() {
            return RunWith.class;
        }

        @Override
        public Class<? extends Runner> value() {
            return JUnitPlatform.class;
        }
    };

    /**
     * Sputnik is the test runner for Spock specification. This method Load the spec class and
     * Sputnik class with Robolectric sandbox, so that Robolectric can intercept the Android API
     * code. That's how we bridge Spock framework and Robolectric together.
     *
     * @param specClass the Specification class to be run under Sputnik
     */
    private Runner createSputnik(Class<? extends Specification> specClass) {

        Class<Object> bootstrappedTestClass = sdkEnvironment.bootstrappedClass(specClass);
        
        // Here we do some black magic to make JUnit see the runner as JUnitPlatform which prevents recursion in DefensiveAllDefaultPossibilitiesBuilder$DefensiveAnnotatedBuilder  
        RunWith current = bootstrappedTestClass.getAnnotation(RunWith.class);
        alterAnnotationValueJDK8(bootstrappedTestClass, RunWith.class, JUnitPlatformRunWithAnnotation);

        try {
            return (Runner) sdkEnvironment
                    .bootstrappedClass(JUnitPlatform.class)
                    .getConstructor(Class.class)
                    .newInstance(bootstrappedTestClass);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            alterAnnotationValueJDK8(bootstrappedTestClass, RunWith.class, current);
        }
    }

    /**
     * Register an interceptor to specInfo of every method in specification.
     */
    private void registerSpec() {

        Constructor<Object> interceptorConstructor = getInterceptorConstructor();

        for (Method method : sputnik.getClass().getDeclaredMethods()) {
            Object specInfo = getSpec(method);
            if (specInfo != null) {
                try {
                    // ElectricSpockInterceptor register itself to SpecInfo on construction,
                    // no need to keep a ref here
                    interceptorConstructor.newInstance(specInfo, containedRunner);
                } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }

    /**
     * Get the SpecInfo from Specification. However, the SpecInfo instance it return will be under
     * Robolectric sandbox, so it cannot be casted directly to SpecInfo statically.
     *
     * @param method the getSpec method
     * @return the SpecInfo object loaded under Robolectric sandbox
     */
    private Object getSpec(Method method) {

        if (method.getName().equals("getSpec")) {
            method.setAccessible(true);
            try {
                Object specInfo = method.invoke(sputnik);
                if (specInfo == null) {
                    throw new RuntimeException("specInfo is null!");
                }
                if (specInfo.getClass() != specInfoClass) {
                    throw new RuntimeException("Failed to obtain SpecInfo instance from getSpec method. Instance of '"
                            + specInfo.getClass().getName() + "' is obtained");
                }
                return specInfo;
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        } else {
            return null;
        }
    }

    /**
     * Get a sandboxed constructor of interceptor
     *
     * @return the interceptor constructor
     */
    private Constructor<Object> getInterceptorConstructor() {

        try {
            return sdkEnvironment
                    .bootstrappedClass(ElectricSpockInterceptor.class)
                    .getConstructor(
                            specInfoClass,
                            ContainedRobolectricTestRunner.class
                    );
        } catch (NoSuchMethodException e) {
            // it should not happen in production code as the class
            // ElectricSpockInterceptor is known
            throw new RuntimeException(e);
        }

    }

    public Description getDescription() {

        Description originalDesc = sputnik.getDescription();

        Class<?> testClass = originalDesc.getTestClass();
        if (testClass == null) throw new RuntimeException("Unexpected null testClass");

        String title = null;
        Annotation[] annotations = testClass.getAnnotations();
        for (Annotation a : annotations) {
            if (a instanceof Title) {
                title = ((Title) a).value();
                break;
            }
        }

        Description overridedDesc = Description.createSuiteDescription(
                title == null ? testClass.getName() : title
        );
        for (Description d : originalDesc.getChildren()) {
            overridedDesc.addChild(d);
        }

        return overridedDesc;

    }

    public void run(RunNotifier notifier) {
        sputnik.run(notifier);
    }

    public void filter(Filter filter) throws NoTestsRemainException {
        ((Filterable) sputnik).filter(filter);
    }

    public void sort(Sorter sorter) {
        ((Sortable) sputnik).sort(sorter);
    }

}
