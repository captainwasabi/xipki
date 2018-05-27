/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.dbtool.port.ocsp;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.dbtool.port.DbPortWorker;
import org.xipki.ca.dbtool.port.DbPorter;
import org.xipki.common.util.IoUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.common.util.StringUtil;
import org.xipki.datasource.DataSourceFactory;
import org.xipki.datasource.DataSourceWrapper;
import org.xipki.password.PasswordResolver;
import org.xipki.password.PasswordResolverException;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class OcspFromCaDbImportWorker extends DbPortWorker {

  private static final Logger LOG = LoggerFactory.getLogger(OcspFromCaDbImportWorker.class);

  private final DataSourceWrapper datasource;

  private final String publisherName;

  private final boolean resume;

  private final String srcFolder;

  private final int batchEntriesPerCommit;

  public OcspFromCaDbImportWorker(DataSourceFactory datasourceFactory,
      PasswordResolver passwordResolver, String dbConfFile, String publisherName,
      boolean resume, String srcFolder, int batchEntriesPerCommit, boolean evaluateOnly)
      throws PasswordResolverException, IOException {
    ParamUtil.requireNonNull("dbConfFile", dbConfFile);
    ParamUtil.requireNonNull("datasourceFactory", datasourceFactory);

    Properties props = DbPorter.getDbConfProperties(
        new FileInputStream(IoUtil.expandFilepath(dbConfFile)));
    this.datasource = datasourceFactory.createDataSource("ds-" + dbConfFile, props,
        passwordResolver);
    this.publisherName = publisherName;
    this.resume = resume;
    this.srcFolder = IoUtil.expandFilepath(srcFolder);
    this.batchEntriesPerCommit = batchEntriesPerCommit;
  }

  @Override
  protected void run0() throws Exception {
    long start = System.currentTimeMillis();
    // CertStore
    try {
      OcspCertStoreFromCaDbImporter certStoreImporter = new OcspCertStoreFromCaDbImporter(
          datasource, srcFolder, publisherName, batchEntriesPerCommit, resume, stopMe);
      certStoreImporter.importToDb();
      certStoreImporter.shutdown();
    } finally {
      try {
        datasource.close();
      } catch (Throwable th) {
        LOG.error("datasource.close()", th);
      }
      long end = System.currentTimeMillis();
      System.out.println("finished in " + StringUtil.formatTime((end - start) / 1000, false));
    }
  }

}
