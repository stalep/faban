/* The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.sun.com/cddl/cddl.html or
 * install_dir/legal/LICENSE
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at install_dir/legal/LICENSE.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: DriverContext.java,v 1.2 2006/06/29 19:38:37 akara Exp $
 *
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.faban.driver.core;

import com.sun.faban.driver.CustomMetrics;
import com.sun.faban.driver.Timing;
import com.sun.faban.driver.util.Random;
import com.sun.faban.driver.util.Timer;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.HashMap;
import java.util.logging.Logger;

/**
 * DriverContext is the point of communication between the
 * developer-provided driver and the Faban driver framework.
 * Each thread has it's own context.<p>
 * This class provides the actual implementation and is
 * located in the core package to get direct access to all
 * the restricted/needed classes and members.
 *
 * @author Akara Sucharitakul
 */
public class DriverContext extends com.sun.faban.driver.DriverContext {

    /** Thread local used for obtaining the context. */
    private static ThreadLocal localContext = new InheritableThreadLocal();

    /** The thread associated with this context. */
    AgentThread agentThread;
    
    /** The timing structure for this thread/context. */
    TimingInfo timingInfo = new TimingInfo();

    /** The central timer. */
    Timer timer;

    /**
     * Flag whether pause is supported with the current protocol.
     * Default is true. Protocols that may not be request/response but
     * may have concurrent inbound and outbound traffic, AND wishes to
     * support auto timing should set this flag to false. The default is true.
     */
    boolean pauseSupported = true;

    /** The start time of the last pause, -1 if no pause. */
    int pauseStart;

    /** Context-specific logger. */
    Logger logger;

    /** The properties hashmap. It is lazy initialized. */
    static HashMap<String, String[]> properties;

    /** Class name of this class. */
    private String className;

    /** The XPath instance used to evaluate the XPaths. */
    private XPath xPathInstance;

    /**
     * Obtains the DriverContext associated with this thread.
     * @return the associated DriverContext
     */
    public static DriverContext getContext() {
        return (DriverContext) localContext.get();
    }

    /**
     * Constructs a DriverContext. Called only from AgentThread.
     * @param thread The AgentThread used by this context
     */
    DriverContext(AgentThread thread, Timer timer) {
        className = getClass().getName();
        agentThread = thread;
        this.timer = timer;
        localContext.set(this);
    }

    /**
     * Obtains the scale or scaling rate of the current run.
     *
     * @return the current run's scaling rate
     */
    public int getScale() {
        return agentThread.runInfo.scale;
    }

    /**
     * Obtains the global thread id for this context's thread. The thread id
     * is unique for each driver type.
     * @return the global agentImpl thread id
     */
    public int getThreadId() {
        return agentThread.id;
    }

    /**
     * Obtains the agentImplImpl id for this agentImplImpl.
     * @return the current agentImpl's id
     */
    public int getAgentId() {
        return agentThread.runInfo.agentInfo.agentNumber;
    }

    /**
     * Obtains the driver's name as annotated in the driver class.
     * @return the driver name
     */
    public String getDriverName() {
        return agentThread.driverConfig.name;
    }

    /**
     * Obtains the logger to be used by the calling driver.
     * @return the appropriate logger
     */
    public Logger getLogger() {
        if (logger == null) {
            logger = Logger.getLogger(agentThread.driverConfig.
                    className + '.' + agentThread.id);
            if (agentThread.runInfo.logHandler != null)
                logger.addHandler(agentThread.runInfo.logHandler);
        }
        return logger;
    }

    /**
     * Obtains the name of the operation currently executing.
     * @return the current operation's name
     */
    public String getCurrentOperation() {
        return agentThread.driverConfig.operations[
                agentThread.currentOperation].name;
    }

    /**
     * Obtains the per-thread random value generator. Drivers
     * should use this random value generator and not instantiate
     * their own.
     * @return The random value generator
     */
    public Random getRandom() {
        return agentThread.random;
    }

    /**
     * Resets the state of the current mix to start off at the beginning
     * of the mix. For stateless mixes such as FlatMix, this operation
     * does nothing.
     */
    public void resetMix() {
        agentThread.selector[agentThread.mixId].reset();
    }

    /**
     * Attaches a custom metrics object to the primary metrics.
     * This should be done by the driver at initialization time.
     * Only one custom metrics can be attached. Subsequent calls
     * to this method replaces the previously attached metrics.
     * @param metrics The custom metrics to be replaced
     */
    public void attachMetrics(CustomMetrics metrics) {
        agentThread.metrics.attachment = metrics;
    }

    /**
     * Parses the properties DOM tree in puts the output into a HashMap.
     * Returns properties so that we do not have the effect of double-checks.
     * @return The resulting map
     */
    private static synchronized HashMap<String, String[]> parseProperties(
            Element propertiesElement) {
        if (properties == null) {
            NodeList list = propertiesElement.getElementsByTagName("property");
            int length = list.getLength();
            HashMap<String, String[]> props =
                    new HashMap<String, String[]>(length);
            for (int i = 0; i < length; i++) {
                Element propertyElement = (Element) list.item(i);
                Attr attr = propertyElement.getAttributeNode("name");
                if (attr != null)
                    props.put(attr.getValue(), getValue(propertyElement));
                NodeList nameList =
                        propertyElement.getElementsByTagName("name");
                if (nameList.getLength() != 1)
                    continue;
                Element nameElement = (Element) nameList.item(0);
                String name = nameElement.getFirstChild().getNodeValue();
                if (name != null)
                    props.put(name, getValue(propertyElement));
            }
            properties = props;
        }
        return properties;
    }

    /**
     * Gets the value of a property DOM element.
     * @param propertyElement The DOM element
     * @return The list of associated values
     */
    private static String[] getValue(Element propertyElement) {
        NodeList valueList = propertyElement.getElementsByTagName("value");
        String[] values;
        int length = valueList.getLength();
        if (length >= 1) {
            values = new String[length];
            for (int i = 0; i < length; i++) {
                Node valueNode = valueList.item(i).getFirstChild();
                values[i] = valueNode == null ? "" : valueNode.getNodeValue();
            }
        } else {
            values = new String[1];
            Node valueNode = propertyElement.getFirstChild();
            values[0] = valueNode == null ? "" : valueNode.getNodeValue();
        }
        return values;
    }

    /**
     * Obtains a single-value property from the configuration. If the name
     * of a multi-value property is given, only one value is returned.
     * It is undefined as to which value in the list is returned.
     *
     * @param name The property name
     * @return The property value, or null if there is no such property
     */
    public String getProperty(String name) {
        if (properties == null)
            properties = parseProperties(getPropertiesNode());
        String[] value = properties.get(name);
        if (value == null)
            return null;
        return value[0];
    }

    /**
     * Obtains a multiple-value property from the configuration. A
     * single-value property will be returned as an array of dimension 1.
     *
     * @param name The property name
     * @return The property values
     */
    public String[] getPropertyValues(String name) {
        if (properties == null)
            properties = parseProperties(getPropertiesNode());
        return properties.get(name);
    }

    /**
     * Obtains the reference to the whole properties element as configured
     * in the driverConfig element of this driver in the config file. This
     * method allows custom free-form structures but the driver will need
     * to spend the effort walking the DOM tree.
     *
     * @return The DOM tree representing the properties node
     */
    public Element getPropertiesNode() {
        return agentThread.driverConfig.properties;
    }

    /**
     * Checks whether the driver is currently in steady state or not.
     * This method needs to be called after the critical section of the
     * operation. The transaction times must have been recorded in order
     * to establish whether or not the transaction is in steady state.
     * @return True if in steady state, false if not.
     */
    public boolean isTxSteadyState() {
        return agentThread.isSteadyState();
    }

    /**
     * Reads the element or attribute by it's XPath. The XPath is evaluated
     * from the root of the configuration file.
     *
     * @param xPath The XPath to evaluate.
     * @return The element or attribute value defined by the XPath
     * @exception XPathExpressionException If the given XPath has an error
     */
    public String getXPathValue(String xPath) throws XPathExpressionException {
        if (xPathInstance == null) {
            XPathFactory xf = XPathFactory.newInstance();
            xPathInstance = xf.newXPath();
        }
        return xPathInstance.evaluate(xPath,
                agentThread.driverConfig.rootElement);
    }

    /**
     * Records the start and end time of the critical section of an operation.
     * This operation may block until the appropriate start time for the
     * operation has arrived. There is no blocking for the end time.
     * This method is for use in the driver code to demarcate critical
     * sections.
     * @throws IllegalStateException if the operation uses auto timing
     */
    public void recordTime() {
        if (agentThread.driverConfig.operations[agentThread.currentOperation].
                timing != Timing.MANUAL) {
            String msg = "Driver: " + getDriverName() + ", Operation: " +
                    getCurrentOperation() + ", timing: MANUAL illegal call " +
                    "to recordTime() in driver code.";
            logger.severe(msg);
            IllegalStateException e = new IllegalStateException(msg);
            logger.throwing(className, "recordTime", e);
            throw e;
        }
        if (timingInfo != null)
            if (timingInfo.invokeTime == -1) {
                timer.sleep(timingInfo.intendedInvokeTime);
                // But since sleep may not be exact, we get the time again here.
                timingInfo.invokeTime = timer.getTime();
            } else if (pauseStart != -1) { // The critical section was paused.
                timingInfo.pauseTime += timer.getTime() - pauseStart;
                pauseStart = -1;
            } else {
                timingInfo.respondTime = timer.getTime();
            }
    }

    /**
     * Pauses the critical section so that operations made during the pause
     * do not count into the response time. If Timing.AUTO is used, the pause
     * ends automatically when the next request is sent to the server. For
     * manual timing, the next call to recordTime ends the pause. Calls
     * pauseTime when the critical section is already paused are simply ignored. 
     */
    public void pauseTime() {
        if (agentThread.driverConfig.operations[agentThread.currentOperation].
                timing != Timing.MANUAL) {
            String msg = "Driver: " + getDriverName() + ", Operation: " +
                    getCurrentOperation() + ", timing: MANUAL illegal call " +
                    "to pauseTime() in driver code.";
            logger.severe(msg);
            IllegalStateException e = new IllegalStateException(msg);
            logger.throwing(className, "recordTime", e);
            throw e;
        }
        if (pauseStart == -1)
            pauseStart = timer.getTime();
    }

    /**
     * Obtains a relative time, in milliseconds. This time is relative to
     * a certain time at the beginning of the benchmark run and does not
     * represent a wall clock time. All agents will have the same reference
     * time. Use this time to check time durations during the benchmark run.
     *
     * @return The relative time of the benchmark run
     */
    public int getTime() {
        return timer.getTime();
    }

    /**
     * Property whether pause is supported with the current protocol.
     * Default is true. Protocols that may not be request/response but
     * may have concurrent inbound and outbound traffic, AND wishes to
     * support auto timing should set this flag to false. The default is true.
     *
     * @return The current setting of the pauseSupported property.
     */
    public boolean isPauseSupported() {
        return pauseSupported;
    }

    /**
     * Property whether pause is supported with the current protocol.
     * Default is true. Protocols that may not be request/response but
     * may have concurrent inbound and outbound traffic, AND wishes to
     * support auto timing should set this flag to false. The default is true.
     *
     * @param pause The new setting of the pauseSupported property.
     */
    public void setPauseSupported(boolean pause) {
        pauseSupported = pause;
    }

    /**
     * Records the start time of an operation. This method is not
     * exposed through the interface and is only used by the transport
     * facilities.
     */
    public void recordStartTime() {
        if (timingInfo != null && agentThread.driverConfig.operations[
                agentThread.currentOperation].timing == Timing.AUTO) {
            if (timingInfo.invokeTime == -1) {
                timer.sleep(timingInfo.intendedInvokeTime);
                // But since sleep may not be exact, we get the time again here.
                timingInfo.invokeTime = timer.getTime();
            } else if (pauseSupported && timingInfo.respondTime != -1) {
                // Some response already read, then transmit again.
                // In this case the time from last receive to this transmit
                // is the pause time ...
                int lastResponse = timingInfo.respondTime;

                // We set the pause time only on the first byte transmitted.
                timingInfo.respondTime = -1;

                timingInfo.pauseTime += timer.getTime() - lastResponse;
            }
        }
    }

    /**
     * Records the end time of an operation. This method is not
     * exposed through the interface and is only used by the transport
     * facilities.
     */
    public void recordEndTime() {
        if (timingInfo != null && agentThread.driverConfig.operations[
                agentThread.currentOperation].timing == Timing.AUTO)
            timingInfo.respondTime = timer.getTime();
    }

    /**
     * Sets the intended invocation time for the next invocation
     * on this thread. This is called from AgentThread only.
     * @param time The time to invoke
     */
    void setInvokeTime(int time) {

        // Then set the intended start time.
        timingInfo.intendedInvokeTime = time;
        // And set the other times to invalid.
        timingInfo.invokeTime = -1;
        timingInfo.respondTime = -1;
        timingInfo.pauseTime = 0;
        pauseStart = -1;
    }

    /**
     * TimingInfo is a value object that contains individual
     * timing records for each operation.
     */
    public static class TimingInfo {
        public int intendedInvokeTime = -1;
        public int invokeTime = -1;
        public int respondTime = -1;
        public int pauseTime = 0;
    }
}