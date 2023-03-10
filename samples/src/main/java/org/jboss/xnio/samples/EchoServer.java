/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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

package org.jboss.xnio.samples;

import org.jboss.xnio.IoUtils;
import org.jboss.xnio.Xnio;
import org.jboss.xnio.OptionMap;
import org.jboss.xnio.TcpServer;
import org.jboss.xnio.Options;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * A simple echo server that runs for 30 seconds and then shuts down.  Based on the standalone API.
 */
public final class EchoServer {
    private EchoServer() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        final Xnio xnio = Xnio.create();
        try {
            final TcpServer tcpServer = xnio.createTcpServer(new EchoHandler(), OptionMap.builder().set(Options.REUSE_ADDRESSES, Boolean.FALSE).getMap());
            try {
                tcpServer.bind(new InetSocketAddress(12345)).await();
                Thread.sleep(30000L);
            } finally {
                IoUtils.safeClose(tcpServer);
            }
        } finally {
            IoUtils.safeClose(xnio);
        }
    }
}
