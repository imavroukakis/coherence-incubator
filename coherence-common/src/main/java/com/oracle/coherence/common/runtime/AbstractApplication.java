/*
 * File: AbstractApplication.java
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * The contents of this file are subject to the terms and conditions of 
 * the Common Development and Distribution License 1.0 (the "License").
 *
 * You may not use this file except in compliance with the License.
 *
 * You can obtain a copy of the License by consulting the LICENSE.txt file
 * distributed with this file, or by consulting
 * or https://oss.oracle.com/licenses/CDDL
 *
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file LICENSE.txt.
 *
 * MODIFICATIONS:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 */

package com.oracle.coherence.common.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

import java.lang.reflect.Field;

import java.util.Properties;

/**
 * <strong>This package is now deprecated.  Please use the com.oracle.tools package instead.</strong>
 * <p>
 * An {@link AbstractApplication} is a base implementation for an {@link Application} that
 * uses a {@link Process} as a means of controlling the said {@link Application} at the operating system level.
 * <p>
 * Copyright (c) 2010. All Rights Reserved. Oracle Corporation.<br>
 * Oracle is a registered trademark of Oracle Corporation and/or its affiliates.
 *
 * @author Brian Oliver
 * @author Harvey Raja
 */
@Deprecated
public abstract class AbstractApplication implements Application
{
    /**
     * The {@link Process} representing the runtime {@link Application}.
     */
    private final Process m_process;

    /**
     * The name of the {@link Application}.
     */
    private String m_name;

    /**
     * The {@link ApplicationConsole} that will be used for the {@link Application} I/O.
     */
    private final ApplicationConsole m_console;

    /**
     * The environment variables used when establishing the {@link Application}.
     */
    private Properties m_environmentVariables;

    /**
     * The {@link Thread} that is used to capture output from the underlying {@link Process}.
     */
    private Thread m_thread;

    /**
     * The os process id this abstract application represents
     */
    private long m_pid = -1;


    /**
     * Standard Constructor.
     *
     * @param process               The {@link Process} representing the {@link Application}.
     * @param name                  The name of the application.
     * @param console               The {@link ApplicationConsole} that will be used for I/O by the {@link Application}.
     * @param environmentVariables  The environment variables used when establishing the {@link Application}.
     */
    public AbstractApplication(Process process,
                               String name,
                               ApplicationConsole console,
                               Properties environmentVariables)
    {
        m_process              = process;
        m_name                 = name;
        m_console              = console == null ? new SystemApplicationConsole() : console;
        m_environmentVariables = environmentVariables;
        m_pid                  = determinePID(process);

        // start a thread to capture the output and redirect it to the console
        this.m_thread = new Thread(new Runnable()
        {
            public void run()
            {
                long lineNumber = 1;

                try
                {
                    BufferedReader reader =
                        new BufferedReader(new InputStreamReader(new BufferedInputStream(AbstractApplication.this
                            .m_process.getInputStream())));

                    while (true)
                    {
                        String line = reader.readLine();

                        if (line == null)
                        {
                            break;
                        }

                        AbstractApplication.this.m_console.printf("[%s%s] %4d: %s\n",
                                                                  AbstractApplication.this.m_name,
                                                                  m_pid < 0 ? "" : ":" + m_pid,
                                                                  lineNumber++,
                                                                  line);
                    }
                }
                catch (Exception exception)
                {
                    // deliberately empty as we safely assume exceptions are always due to process termination.
                }

                AbstractApplication.this.m_console.printf("[%s%s] %4d: (terminated)\n",
                                                          AbstractApplication.this.m_name,
                                                          m_pid < 0 ? "" : ":" + m_pid,
                                                          lineNumber++);
            }
        });

        m_thread.setDaemon(true);
        m_thread.start();
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public Properties getEnvironmentVariables()
    {
        return m_environmentVariables;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public String getName()
    {
        return m_name;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy()
    {
        // terminate the ioThread that is reading from the process
        m_thread.interrupt();

        // close the io streams being used by the process
        try
        {
            m_process.getInputStream().close();
        }
        catch (IOException e)
        {
            // nothing to do here as we don't care
        }

        try
        {
            m_process.getOutputStream().close();
        }
        catch (IOException e)
        {
            // nothing to do here as we don't care
        }

        try
        {
            m_process.getErrorStream().close();
        }
        catch (IOException e)
        {
            // nothing to do here as we don't care
        }

        // terminate the process
        m_process.destroy();

        // wait for it to actually terminate (because the above line may not finish for a while)
        // (if we don't wait the process may be left hanging/orphanned)
        try
        {
            m_process.waitFor();
        }
        catch (InterruptedException e)
        {
            // nothing to do here as we don't care
        }
    }


    /**
     * Obtain the operating system process id for the {@link Application}.
     *
     * @return The process id or -1 if it can't be determined.
     */
    public long getPid()
    {
        return m_pid;
    }


    /**
     * Determine the {@link Process} id for the {@link Application}
     * </p>
     *
     * @param p the {@link Process} to attain the process id from
     *
     * @return The {@link Process} id or -1 if it can't be determined.
     */
    private long determinePID(final Process p)
    {
        long pid = -1;

        try
        {
            // Unix variants incl. OSX
            if (p.getClass().getSimpleName().equals("UNIXProcess"))
            {
                final Class<?> clazz = p.getClass();
                final Field    pidF  = clazz.getDeclaredField("pid");

                pidF.setAccessible(true);

                Object oPid = pidF.get(p);

                if (oPid instanceof Number)
                {
                    pid = ((Number) oPid).longValue();
                }
                else if (oPid instanceof String)
                {
                    pid = Long.parseLong((String) oPid);
                }
            }

            // Windows processes, i.e. Win32Process or ProcessImpl
            else
            {
                RuntimeMXBean rtb      = ManagementFactory.getRuntimeMXBean();
                final String  sProcess = rtb.getName();
                final int     iPID     = sProcess.indexOf('@');

                if (iPID > 0)
                {
                    String sPID = sProcess.substring(0, iPID);

                    pid = Long.parseLong(sPID);
                }
            }
        }
        catch (SecurityException e)
        {
        }
        catch (NoSuchFieldException e)
        {
        }
        catch (IllegalArgumentException e)
        {
        }
        catch (IllegalAccessException e)
        {
        }

        return pid;
    }
}