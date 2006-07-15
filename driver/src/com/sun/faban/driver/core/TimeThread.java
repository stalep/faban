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
 * $Id: TimeThread.java,v 1.2 2006/06/29 19:38:37 akara Exp $
 *
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.faban.driver.core;

import com.sun.faban.driver.FatalException;

import java.lang.reflect.InvocationTargetException;
import java.util.logging.Level;


/**
 * A driver thread that controls the run by ramp up, steady state,
 * and ramp down times.
 *
 * @author Akara Sucharitakul
 */
public class TimeThread extends AgentThread {

    /**
     * Allocates and initializes the timing structures which is specific
     * to the pseudo-thread dimensions.
     */
    void initTimes() {
        delayTime = new int[1];
        startTime = new int[1];
        endTime = new int[1];

        // This is the start and end time of the previous operation used to
        // calculate the start of the next operation. We set it to the current
        // time for the first operation to have a reference point. In fact,
        // any reference point is OK.
        startTime[0] = timer.getTime();
        endTime[0] = startTime[0];
    }

    /**
     * Each thread executes in the doRun method until the benchmark time is up
     * The main loop chooses a tx. type according to the mix specified in
     * the parameter file and calls the appropriate transaction
     * method to do the job.
   	 * The stats for the entire run are stored in a Metrics object
   	 * which is returned to the Agent via the getResult() method.
     * @see Metrics
     */
    void doRun() {

        driverContext = new DriverContext(this, timer);

        try {
            driver = driverClass.newInstance();
        } catch (Exception e) {
            logger.log(Level.SEVERE, name +
                    ": Error initializing driver object.", e);
            agent.abortRun();
            return; // Terminate this thread immediately
        }

        // Notify the agent that we have started successfully.
        agent.threadStartLatch.countDown();

        selector = new Mix.Selector[1];
        selector[0] = driverConfig.mix[0].selector(random);

        if (runInfo.simultaneousStart) {
            waitStartTime();

            // Calculate time periods
            // Note that the time periods are in secs, need to convert
            endRampUp = runInfo.benchStartTime + runInfo.rampUp * 1000;
            endStdyState = endRampUp + runInfo.stdyState * 1000;
            endRampDown = endStdyState + runInfo.rampDown * 1000;
        }

        logger.fine(name + ": Start of run.");

        // Loop until time or cycles are up
        driverLoop:
        while (!stopped) {

            if (!runInfo.simultaneousStart && !startTimeSet &&
                    agent.timeSetLatch.getCount() == 0) {
                startTimeSet = true;

                // Calculate time periods
                // Note that the time periods are in secs, need to convert
                endRampUp = runInfo.benchStartTime + runInfo.rampUp * 1000;
                endStdyState = endRampUp + runInfo.stdyState * 1000;
                endRampDown = endStdyState + runInfo.rampDown * 1000;
            }

            // Select the operation
            currentOperation = selector[0].select();
            BenchmarkDefinition.Operation op =
                    driverConfig.operations[currentOperation];

            // endRampDown is only valid if start time is set.
            // If the start time of next tx is beyond the end
            // of the ramp down, just stop right here.
            int invokeTime = getInvokeTime(op, mixId);

            if (startTimeSet && invokeTime >= endRampDown)
                break driverLoop;

            driverContext.setInvokeTime(invokeTime);

            // Invoke the operation
            try {
                op.m.invoke(driver);
                validateTimeCompletion(op);
                checkRamp();
                metrics.recordTx();
                metrics.recordDelayTime();
            } catch (InvocationTargetException e) {
                // An invocation target exception is caused by another
                // exception thrown by the operation directly.
                Throwable cause = e.getCause();
                checkFatal(cause, op);

                // We have to fix up the invoke/respond times to have valid
                // values and not -1.

                // In case of exception, invokeTime or even respondTime may
                // still be -1.
                DriverContext.TimingInfo timingInfo = driverContext.timingInfo;
                // If it never waited, we'll see whether we can just use the
                // previous start and end times.
                if (timingInfo.invokeTime == -1) {
                    int currentTime = timer.getTime();
                    if (currentTime < timingInfo.intendedInvokeTime) {
                        // No time change, no need to checkRamp
                        metrics.recordError();
                        logError(cause, op);
                        continue driverLoop;
                    } else {
                        // Too late, we'll need to use the real time
                        // for both invoke and respond time.
                        timingInfo.invokeTime = timer.getTime();
                        timingInfo.respondTime = timingInfo.invokeTime;
                        checkRamp();
                        metrics.recordError();
                        logError(cause, op);
                        // The delay time is invalid,
                        // we cannot record in this case.
                    }
                } else if (timingInfo.respondTime == -1) {
                    timingInfo.respondTime = timer.getTime();
                    checkRamp();
                    metrics.recordError();
                    logError(cause, op);
                    metrics.recordDelayTime();
                } else { // All times are there
                    checkRamp();
                    metrics.recordError();
                    logError(cause, op);
                    metrics.recordDelayTime();
                }
             } catch (IllegalAccessException e) {
                logger.log(Level.SEVERE, name + "." + op.m.getName() + ": "
                        + e.getMessage(), e);
                agent.abortRun();
                return;
            }

            startTime[mixId] = driverContext.timingInfo.invokeTime;
            endTime[mixId] = driverContext.timingInfo.respondTime;

            if (startTimeSet && endTime[mixId] >= endRampDown)
                break driverLoop;
        }
        logger.fine(name + ": End of run.");
    }

    /**
     * Checks whether the last operation is in the ramp-up or ramp-down or
     * not. Updates the inRamp parameter accordingly.
     */
    void checkRamp() {
        inRamp = !_isSteadyState();
    }

    /**
     * Tests whether the last operation is in steady state or not. This is
     * called by the driver from within the operation so we need to be careful
     * not to change run control parameters. This method only reads the stats.
     * @return True if the last operation is in steady state, false otherwise.
     */
    boolean isSteadyState() {
        if (driverContext.timingInfo.respondTime == -1)
            throw new FatalException("isTxSteadyState called before response " +
                    "time capture. Cannot determine tx in steady state or " +
                    "not. This is a bug in the driver code.");

        return _isSteadyState();
    }

    /**
     * Internally checks the steady state.
     * @return Whether or not the last operation is in steady state
     */
    private boolean _isSteadyState() {
        return startTimeSet &&
                driverContext.timingInfo.invokeTime >= endRampUp &&
                driverContext.timingInfo.respondTime < endStdyState;
    }
}