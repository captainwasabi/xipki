/*
 *
 * This file is part of the XiPKI project.
 * Copyright (c) 2013 - 2016 Lijun Liao
 * Author: Lijun Liao
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License version 3
 * as published by the Free Software Foundation with the addition of the
 * following permission added to Section 15 as permitted in Section 7(a):
 *
 * FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
 * THE AUTHOR LIJUN LIAO. LIJUN LIAO DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
 * OF THIRD PARTY RIGHTS.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * The interactive user interfaces in modified source and object code versions
 * of this program must display Appropriate Legal Notices, as required under
 * Section 5 of the GNU Affero General Public License.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license. Buying such a license is mandatory as soon as you
 * develop commercial activities involving the XiPKI software without
 * disclosing the source code of your own applications.
 *
 * For more information, please contact Lijun Liao at this
 * address: lijun.liao@gmail.com
 */

package org.xipki.pki.ca.api.profile.x509;

import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.commons.common.util.ParamUtil;
import org.xipki.pki.ca.api.profile.CertprofileException;

/**
 * @author Lijun Liao
 * @since 2.0.0
 */

public class X509CertprofileFactoryRegisterImpl implements X509CertprofileFactoryRegister {

    private static final Logger LOG = LoggerFactory.getLogger(
            X509CertprofileFactoryRegisterImpl.class);

    private ConcurrentLinkedDeque<X509CertprofileFactory> services =
            new ConcurrentLinkedDeque<X509CertprofileFactory>();

    @Override
    public X509Certprofile newCertprofile(
            final String type,
            final long timeout)
    throws CertprofileException {
        ParamUtil.requireNonBlank("type", type);
        ParamUtil.requireMin("timeout", timeout, 0);

        long start = System.currentTimeMillis();

        X509Certprofile certProfile = null;
        while (true) {
            for (X509CertprofileFactory service : services) {
                if (service.canCreateProfile(type)) {
                    certProfile = service.newCertprofile(type);
                }
            }

            if (timeout != 0 || System.currentTimeMillis() - start > timeout) {
                break;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {// CHECKSTYLE:SKIP
            }
        }

        if (certProfile == null) {
            throw new CertprofileException("could not new Certprofile");
        }

        return certProfile;
    }

    public void bindService(
            final X509CertprofileFactory service) {
        //might be null if dependency is optional
        if (service == null) {
            LOG.debug("bindService invoked with null.");
            return;
        }

        boolean replaced = services.remove(service);
        services.add(service);

        String action = replaced
                ? "replaced"
                : "added";
        LOG.debug("{} X509CertprofileFactory binding for {}", action, service);
    }

    public void unbindService(
            final X509CertprofileFactory service) {
        //might be null if dependency is optional
        if (service == null) {
            LOG.debug("unbindService invoked with null.");
            return;
        }

        if (services.remove(service)) {
            LOG.debug("removed X509CertprofileFactory binding for {}", service);
        } else {
            LOG.debug("no X509CertprofileFactory binding found to remove for '{}'", service);
        }
    }

}
