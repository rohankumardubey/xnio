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

package org.jboss.xnio.channels;

/**
 * The result of a multipoint message read.
 *
 * @param <A> the type of address associated with this read result
 *
 * @apiviz.exclude
 */
public interface MultipointReadResult<A> {
    /**
     * Get the source address of the message, if available.
     *
     * @return the source address, or {@code null} if it is unknown
     */
    A getSourceAddress();

    /**
     * Get the destination address of the message, if available.
     *
     * @return the destination address, or {@code null} if it is unknown
     */
    A getDestinationAddress();
}
