/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.clustering.twoclusters;

import static org.jboss.as.test.shared.IntermittentFailure.thisTestIsFailingIntermittently;
import static org.junit.Assert.fail;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.naming.NamingException;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.test.clustering.cluster.AbstractClusteringTestCase;
import org.jboss.as.test.clustering.ejb.EJBDirectory;
import org.jboss.as.test.clustering.twoclusters.bean.common.CommonStatefulSB;
import org.jboss.as.test.clustering.twoclusters.bean.forwarding.AbstractForwardingStatefulSBImpl;
import org.jboss.as.test.clustering.twoclusters.bean.stateful.RemoteStatefulSB;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.common.function.ExceptionSupplier;

/**
 * Test EJBClient functionality across two clusters with fail-over.
 * <p/>
 * A client makes an invocation on one clustered app (on cluster A) which in turn
 * forwards the invocation on a second clustered app (on cluster B).
 * <p/>
 * cluster A = {node0, node1}
 * cluster B = {node2, node3}
 * <p/>
 * Under constant client load, we stop and then restart individual servers.
 * <p/>
 * We expect that client invocations will not be affected.
 *
 * @author Richard Achmatowicz
 * @author Radoslav Husar
 */
@RunWith(Arquillian.class)
@RunAsClient
public abstract class AbstractRemoteEJBTwoClusterTestCase extends AbstractClusteringTestCase {

    @BeforeClass
    public static void beforeClass() {
        thisTestIsFailingIntermittently("WFLY-6224/JBEAP-3432, WFLY-9447/JBEAP-13511, etc.");
    }

    private static long FAILURE_FREE_TIME = TimeoutUtil.adjust(5000);
    private static long SERVER_DOWN_TIME = TimeoutUtil.adjust(5000);
    private static long INVOCATION_WAIT = TimeoutUtil.adjust(100);

    private final ExceptionSupplier<EJBDirectory, NamingException> directorySupplier;
    private final String implementationClass;

    private static final Logger logger = Logger.getLogger(AbstractRemoteEJBTwoClusterTestCase.class);

    AbstractRemoteEJBTwoClusterTestCase(ExceptionSupplier<EJBDirectory, NamingException> directorySupplier, String implementationClass) {
        super(FOUR_NODES);

        this.directorySupplier = directorySupplier;
        this.implementationClass = implementationClass;
    }

    @Deployment(name = DEPLOYMENT_3, managed = false, testable = false)
    @TargetsContainer(NODE_3)
    public static Archive<?> deployment2() {
        return createNonForwardingDeployment();
    }

    @Deployment(name = DEPLOYMENT_4, managed = false, testable = false)
    @TargetsContainer(NODE_4)
    public static Archive<?> deployment3() {
        return createNonForwardingDeployment();
    }

    private static Archive<?> createNonForwardingDeployment() {
        JavaArchive ejbJar = ShrinkWrap.create(JavaArchive.class, AbstractForwardingStatefulSBImpl.MODULE_NAME + ".jar");
        ejbJar.addPackage(CommonStatefulSB.class.getPackage());
        ejbJar.addPackage(RemoteStatefulSB.class.getPackage());
        return ejbJar;
    }

    /**
     * Tests that EJBClient invocations on stateful session beans can still successfully be processed
     * as long as one node in each cluster is available.
     */
    @Test
    public void test() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        try (EJBDirectory directory = directorySupplier.get()) {
            // get the correct forwarder deployment on cluster A
            RemoteStatefulSB bean = directory.lookupStateful(implementationClass, RemoteStatefulSB.class);

            // Allow sufficient time for client to receive full topology
            logger.info("Waiting for clusters to form.");
            Thread.sleep(FAILURE_FREE_TIME);

            int newSerialValue = bean.getSerialAndIncrement();
            logger.debugf("First invocation: serial = %d", newSerialValue);

            ClientInvocationTask client = new ClientInvocationTask(bean, newSerialValue);

            // set up the client invocations
            executor.scheduleWithFixedDelay(client, 0, INVOCATION_WAIT, TimeUnit.MILLISECONDS);

            // a few seconds of non-failure behaviour
            Thread.sleep(FAILURE_FREE_TIME);
            client.assertNoExceptions("at the beginning of the test");

            logger.debug("------ Shutdown clusterA-node0 -----");
            stop(GRACEFUL_SHUTDOWN_TIMEOUT, NODE_1);
            Thread.sleep(SERVER_DOWN_TIME);
            client.assertNoExceptions("after clusterA-node0 was shut down");

            logger.debug("------ Startup clusterA-node0 -----");
            start(NODE_1);
            Thread.sleep(FAILURE_FREE_TIME);
            client.assertNoExceptions("after clusterA-node0 was brought up");

            logger.debug("----- Shutdown clusterA-node1 -----");
            stop(GRACEFUL_SHUTDOWN_TIMEOUT, NODE_2);
            Thread.sleep(SERVER_DOWN_TIME);

            logger.info("------ Startup clusterA-node1 -----");
            start(NODE_2);
            Thread.sleep(FAILURE_FREE_TIME);
            client.assertNoExceptions("after clusterA-node1 was brought back up");

            logger.debug("----- Shutdown clusterB-node0 -----");
            stop(GRACEFUL_SHUTDOWN_TIMEOUT, NODE_3);
            Thread.sleep(SERVER_DOWN_TIME);
            client.assertNoExceptions("after clusterB-node0 was shut down");

            logger.info("------ Startup clusterB-node0 -----");
            start(NODE_3);
            Thread.sleep(FAILURE_FREE_TIME);
            client.assertNoExceptions("after clusterB-node0 was brought back up");

            logger.debug("----- Shutdown clusterB-node1 -----");
            stop(GRACEFUL_SHUTDOWN_TIMEOUT, NODE_4);
            Thread.sleep(SERVER_DOWN_TIME);

            logger.debug("------ Startup clusterB-node1 -----");
            start(NODE_4);
            Thread.sleep(FAILURE_FREE_TIME);

            // final assert
            client.assertNoExceptions("after clusterB-node1 was brought back up");
        } finally {
            executor.shutdownNow();
        }
    }

    private class ClientInvocationTask implements Runnable {
        private final RemoteStatefulSB bean;
        private int expectedSerial;
        private volatile Exception firstException;
        private int invocationCount;

        ClientInvocationTask(RemoteStatefulSB bean, int serial) {
            this.bean = bean;
            this.expectedSerial = serial;
        }

        /**
         * Asserts that there were no exception during the last test period.
         */
        void assertNoExceptions(String when) throws Exception {
            if (firstException != null) {
                logger.error(firstException);
                fail("Client threw an exception " + when + ": " + firstException); // Arrays.stream(firstException.getStackTrace()).map(StackTraceElement::toString).collect(Collectors.joining("\n")));
            }
        }

        @Override
        public void run() {
            invocationCount++;

            try {
                int serial = this.bean.getSerialAndIncrement();
                logger.debugf("EJB client invocation #%d on bean, received serial #%d.", this.invocationCount, serial);

                if (serial != ++expectedSerial) {
                    logger.warnf("Expected (%d) and received serial (%d) numbers do not match! Resetting.", expectedSerial, serial);
                    expectedSerial = serial;
                }
            } catch (Exception clientException) {
                if (this.firstException == null) {
                    this.firstException = clientException;
                }
            }
        }
    }
}