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

package org.xipki.security.pkcs11;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.DSAParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.security.HashAlgo;
import org.xipki.security.X509Cert;
import org.xipki.security.pkcs11.exception.P11DuplicateEntityException;
import org.xipki.security.pkcs11.exception.P11PermissionException;
import org.xipki.security.pkcs11.exception.P11TokenException;
import org.xipki.security.pkcs11.exception.P11UnknownEntityException;
import org.xipki.security.pkcs11.exception.P11UnsupportedMechanismException;
import org.xipki.security.util.AlgorithmUtil;
import org.xipki.security.util.DSAParameterCache;
import org.xipki.security.util.X509Util;
import org.xipki.util.Hex;
import org.xipki.util.LogUtil;
import org.xipki.util.ParamUtil;
import org.xipki.util.StringUtil;

import iaik.pkcs.pkcs11.constants.Functions;
import iaik.pkcs.pkcs11.constants.PKCS11Constants;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public abstract class P11Slot {

  private static final Logger LOG = LoggerFactory.getLogger(P11Slot.class);

  protected final String moduleName;

  protected final P11SlotIdentifier slotId;

  private final boolean readOnly;

  private final SecureRandom random = new SecureRandom();

  private final ConcurrentHashMap<P11ObjectIdentifier, P11Identity> identities =
      new ConcurrentHashMap<>();

  private final ConcurrentHashMap<P11ObjectIdentifier, X509Cert> certificates =
      new ConcurrentHashMap<>();

  private final Set<Long> mechanisms = new HashSet<>();

  private final P11MechanismFilter mechanismFilter;

  protected P11Slot(String moduleName, P11SlotIdentifier slotId, boolean readOnly,
      P11MechanismFilter mechanismFilter) throws P11TokenException {
    this.mechanismFilter = ParamUtil.requireNonNull("mechanismFilter", mechanismFilter);
    this.moduleName = ParamUtil.requireNonBlank("moduleName", moduleName);
    this.slotId = ParamUtil.requireNonNull("slotId", slotId);
    this.readOnly = readOnly;
  }

  /**
   * Returns the hex representation of the bytes.
   *
   * @param bytes
   *          Data to be encoded. Must not be {@code null}.
   * @return the hex representation of the bytes.
   */
  protected static String hex(byte[] bytes) {
    return Hex.encode(bytes);
  }

  /**
   * Returns the hex representation of the bytes.
   *
   * @param bytes
   *          Data to be encoded. Must not be {@code null}.
   * @return the hex representation of the bytes.
   */
  protected static byte[] decodeHex(String hex) {
    return Hex.decode(hex);
  }

  protected static String getDescription(byte[] keyId, char[] keyLabel) {
    return StringUtil.concat("id ", (keyId == null ? "null" : hex(keyId)), " and label ",
        (keyLabel == null ? "null" : new String(keyLabel)));
  }

  protected static String getDescription(byte[] keyId, String keyLabel) {
    return StringUtil.concat("id ", (keyId == null ? "null" : hex(keyId)), " and label ", keyLabel);
  }

  /**
   * Updates the certificate associated with the given {@code objectId} with the given certificate
   * {@code newCert}.
   *
   * @param keyId
   *          Object identifier of the private key. Must not be {@code null}.
   * @param newCert
   *          Certificate to be added. Must not be {@code null}.
   * @throws CertificateException
   *         if process with certificate fails.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  protected abstract void updateCertificate0(P11ObjectIdentifier keyId, X509Certificate newCert)
      throws P11TokenException, CertificateException;

  /**
   * Removes the key (private key, public key, secret key, and certificates) associated with
   * the given identifier {@code objectId}.
   *
   * @param identityId
   *          Identity identifier. Must not be {@code null}.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  protected abstract void removeIdentity0(P11IdentityId identityId) throws P11TokenException;

  /**
   * Adds the certificate to the PKCS#11 token under the given identifier {@code objectId}.
   *
   * @param cert
   *          Certificate to be added. Must not be {@code null}.
   * @param control
   *          Control of the object creation process. Must not be {@code null}.
   * @return the PKCS#11 identifier of the added certificate.
   * @throws CertificateException
   *         if process with certificate fails.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  protected abstract P11ObjectIdentifier addCert0(X509Certificate cert, P11NewObjectControl control)
      throws P11TokenException, CertificateException;

  /**
   * Generates a secret key in the PKCS#11 token.
   *
   * @param keyType
   *          key type
   * @param keysize
   *          key size
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the key within the PKCS#11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  protected abstract P11Identity generateSecretKey0(long keyType, int keysize,
      P11NewKeyControl control) throws P11TokenException;

  /**
   * Imports secret key object in the PKCS#11 token. The key itself will not be generated
   * within the PKCS#11 token.
   *
   * @param keyType
   *          key type.
   * @param keyValue
   *          Key value. Must not be {@code null}.
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the key within the PKCS#P11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  protected abstract P11Identity importSecretKey0(long keyType, byte[] keyValue,
      P11NewKeyControl control) throws P11TokenException;

  /**
   * Generates a DSA keypair.
   *
   * @param p
   *          p of DSA. Must not be {@code null}.
   * @param q
   *          q of DSA. Must not be {@code null}.
   * @param g
   *          g of DSA. Must not be {@code null}.
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the key within the PKCS#P11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  // CHECKSTYLE:SKIP
  protected abstract P11Identity generateDSAKeypair0(BigInteger p, BigInteger q, BigInteger g,
      P11NewKeyControl control) throws P11TokenException;

  /**
   * Generates an EC keypair.
   *
   * @param curveId
   *         Object identifier of the EC curve. Must not be {@code null}.
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the key within the PKCS#P11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  // CHECKSTYLE:SKIP
  protected abstract P11Identity generateECKeypair0(ASN1ObjectIdentifier curveId,
      P11NewKeyControl control) throws P11TokenException;

  /**
   * Generates an SM2p256v1 keypair.
   *
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the key within the PKCS#P11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  // CHECKSTYLE:SKIP
  protected abstract P11Identity generateSM2Keypair0(P11NewKeyControl control)
      throws P11TokenException;

  /**
   * Generates an RSA keypair.
   *
   * @param keysize
   *          key size in bit
   * @param publicExponent
   *          RSA public exponent. Could be {@code null}.
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the key within the PKCS#P11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  // CHECKSTYLE:SKIP
  protected abstract P11Identity generateRSAKeypair0(int keysize, BigInteger publicExponent,
      P11NewKeyControl control) throws P11TokenException;

  protected abstract P11SlotRefreshResult refresh0() throws P11TokenException;

  protected abstract void removeCerts0(P11ObjectIdentifier objectId) throws P11TokenException;

  public abstract void close();

  /**
   * TODO.
   * @param id
   *         Id of the objects to be deleted. At least one of id and label must not be {@code null}.
   * @param label
   *         Label of the objects to be deleted
   * @return how many objects have been deleted
   * @throws P11TokenException
   *           If PKCS#11 error happens.
   */
  public abstract int removeObjects(byte[] id, String label) throws P11TokenException;

  /**
   * Gets certificate with the given identifier {@code id}.
   * @param id
   *          Identifier of the certificate. Must not be {@code null}.
   * @return certificate with the given identifier.
   */
  protected X509Cert getCertForId(byte[] id) {
    for (P11ObjectIdentifier objId : certificates.keySet()) {
      if (objId.matchesId(id)) {
        return certificates.get(objId);
      }
    }
    return null;
  }

  private void updateCaCertsOfIdentities() {
    for (P11Identity identity : identities.values()) {
      updateCaCertsOfIdentity(identity);
    }
  }

  private void updateCaCertsOfIdentity(P11Identity identity) {
    X509Certificate[] certchain = identity.certificateChain();
    if (certchain == null || certchain.length == 0) {
      return;
    }

    X509Certificate[] newCertchain = buildCertPath(certchain[0]);
    if (!Arrays.equals(certchain, newCertchain)) {
      try {
        identity.setCertificates(newCertchain);
      } catch (P11TokenException ex) {
        LOG.warn("could not set certificates for identity {}", identity.getId());
      }
    }
  }

  private X509Certificate[] buildCertPath(X509Certificate cert) {
    List<X509Certificate> certs = new LinkedList<>();
    X509Certificate cur = cert;
    while (cur != null) {
      certs.add(cur);
      cur = getIssuerForCert(cur);
    }
    return certs.toArray(new X509Certificate[0]);
  }

  private X509Certificate getIssuerForCert(X509Certificate cert) {
    try {
      if (X509Util.isSelfSigned(cert)) {
        return null;
      }

      for (X509Cert cert2 : certificates.values()) {
        if (cert2.getCert() == cert) {
          continue;
        }

        if (X509Util.issues(cert2.getCert(), cert)) {
          return cert2.getCert();
        }
      }
    } catch (CertificateEncodingException ex) {
      LOG.warn("invalid encoding of certificate {}", ex.getMessage());
    }
    return null;
  }

  public void refresh() throws P11TokenException {
    P11SlotRefreshResult res = refresh0(); // CHECKSTYLE:SKIP

    mechanisms.clear();
    certificates.clear();
    identities.clear();

    List<Long> ignoreMechs = new ArrayList<>();

    for (Long mech : res.getMechanisms()) {
      if (mechanismFilter.isMechanismPermitted(slotId, mech)) {
        mechanisms.add(mech);
      } else {
        ignoreMechs.add(mech);
      }
    }
    certificates.putAll(res.getCertificates());
    identities.putAll(res.getIdentities());

    updateCaCertsOfIdentities();

    if (LOG.isInfoEnabled()) {
      StringBuilder sb = new StringBuilder();
      sb.append("initialized module ").append(moduleName).append(", slot ").append(slotId);

      sb.append("\nsupported mechanisms:\n");
      List<Long> sortedMechs = new ArrayList<>(mechanisms);
      Collections.sort(sortedMechs);
      for (Long mech : sortedMechs) {
        sb.append("\t").append(Functions.getMechanismDescription(mech)).append("\n");
      }

      sb.append("\nsupported by device but ignored mechanisms:\n");
      if (ignoreMechs.isEmpty()) {
        sb.append("\tNONE\n");
      } else {
        Collections.sort(ignoreMechs);
        for (Long mech : ignoreMechs) {
          sb.append("\t").append(Functions.getMechanismDescription(mech)).append("\n");
        }
      }

      List<P11ObjectIdentifier> ids = getSortedObjectIds(certificates.keySet());
      sb.append(ids.size()).append(" certificates:\n");
      for (P11ObjectIdentifier objectId : ids) {
        X509Cert entity = certificates.get(objectId);
        sb.append("\t").append(objectId);
        sb.append(", subject='").append(entity.getSubject()).append("'\n");
      }

      ids = getSortedObjectIds(identities.keySet());
      sb.append(ids.size()).append(" identities:\n");
      for (P11ObjectIdentifier objectId : ids) {
        P11Identity identity = identities.get(objectId);
        sb.append("\t").append(objectId);
        if (identity.getPublicKey() != null) {
          sb.append(", algo=").append(identity.getPublicKey().getAlgorithm());
          if (identity.getCertificate() != null) {
            String subject = X509Util.getRfc4519Name(
                identity.getCertificate().getSubjectX500Principal());
            sb.append(", subject='").append(subject).append("'");
          }
        } else {
          sb.append(", algo=<symmetric>");
        }
        sb.append("\n");
      }

      LOG.info(sb.toString());
    }
  }

  protected void addIdentity(P11Identity identity) throws P11DuplicateEntityException {
    if (!slotId.equals(identity.getId().getSlotId())) {
      throw new IllegalArgumentException("invalid identity");
    }

    P11ObjectIdentifier keyId = identity.getId().getKeyId();
    if (hasIdentity(keyId)) {
      throw new P11DuplicateEntityException(slotId, keyId);
    }

    identities.put(keyId, identity);
    updateCaCertsOfIdentity(identity);
  }

  public boolean hasIdentity(P11ObjectIdentifier keyId) {
    return identities.containsKey(keyId);
  }

  public Set<Long> getMechanisms() {
    return Collections.unmodifiableSet(mechanisms);
  }

  public boolean supportsMechanism(long mechanism) {
    return mechanisms.contains(mechanism);
  }

  public void assertMechanismSupported(long mechanism)
      throws P11UnsupportedMechanismException {
    if (!mechanisms.contains(mechanism)) {
      throw new P11UnsupportedMechanismException(mechanism, slotId);
    }
  }

  public Set<P11ObjectIdentifier> getIdentityIds() {
    return Collections.unmodifiableSet(identities.keySet());
  }

  public Set<P11ObjectIdentifier> getCertIds() {
    return Collections.unmodifiableSet(certificates.keySet());
  }

  public String getModuleName() {
    return moduleName;
  }

  public P11SlotIdentifier getSlotId() {
    return slotId;
  }

  public boolean isReadOnly() {
    return readOnly;
  }

  public P11Identity getIdentity(P11ObjectIdentifier keyId) throws P11UnknownEntityException {
    P11Identity ident = identities.get(keyId);
    if (ident == null) {
      throw new P11UnknownEntityException(slotId, keyId);
    }
    return ident;
  }

  protected void assertNoIdentityAndCert(byte[] id, String label)
      throws P11DuplicateEntityException {
    if (id == null && label == null) {
      return;
    }

    Set<P11ObjectIdentifier> objectIds = new HashSet<>(identities.keySet());
    objectIds.addAll(certificates.keySet());

    for (P11ObjectIdentifier objectId : objectIds) {
      boolean matchId = (id == null) ? false : objectId.matchesId(id);
      boolean matchLabel = (label == null) ? false : label.equals(objectId.getLabel());

      if (matchId || matchLabel) {
        StringBuilder sb = new StringBuilder("Identity or Certificate with ");
        if (matchId) {
          sb.append("id=0x").append(Hex.encodeUpper(id));
          if (matchLabel) {
            sb.append(" and ");
          }
        }

        if (matchLabel) {
          sb.append("label=").append(label);
        }

        sb.append(" already exists");
        throw new P11DuplicateEntityException(sb.toString());
      }
    }
  }

  public P11ObjectIdentifier getObjectId(byte[] id, String label) {
    if (id == null && label == null) {
      return null;
    }

    for (P11ObjectIdentifier objectId : identities.keySet()) {
      boolean match = true;
      if (id != null) {
        match = objectId.matchesId(id);
      }

      if (label != null) {
        match = label.equals(objectId.getLabel());
      }

      if (match) {
        return objectId;
      }
    }

    for (P11ObjectIdentifier objectId : certificates.keySet()) {
      boolean match = true;
      if (id != null) {
        match = objectId.matchesId(id);
      }

      if (label != null) {
        match = label.equals(objectId.getLabel());
      }

      if (match) {
        return objectId;
      }
    }

    return null;
  }

  public P11IdentityId getIdentityId(byte[] keyId, String keyLabel) {
    if (keyId == null && keyLabel == null) {
      return null;
    }

    for (P11ObjectIdentifier objectId : identities.keySet()) {
      boolean match = true;
      if (keyId != null) {
        match = objectId.matchesId(keyId);
      }

      if (keyLabel != null) {
        match = keyLabel.equals(objectId.getLabel());
      }

      if (match) {
        return identities.get(objectId).getId();
      }
    }

    return null;
  }

  /**
   * Exports the certificate of the given identifier {@code objectId}.
   *
   * @param objectId
   *          Object identifier. Must not be {@code null}.
   * @return the exported certificate
   * @throws CertificateException
   *         if process with certificate fails.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  public X509Certificate exportCert(P11ObjectIdentifier objectId) throws P11TokenException {
    ParamUtil.requireNonNull("objectId", objectId);
    try {
      return getIdentity(objectId).getCertificate();
    } catch (P11UnknownEntityException ex) {
      // CHECKSTYLE:SKIP
    }

    X509Cert cert = certificates.get(objectId);
    if (cert == null) {
      throw new P11UnknownEntityException(slotId, objectId);
    }
    return cert.getCert();
  }

  /**
   * TODO.
   * @param objectId
   *          Object identifier. Must not be {@code null}.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  public void removeCerts(P11ObjectIdentifier objectId) throws P11TokenException {
    ParamUtil.requireNonNull("objectId", objectId);
    assertWritable("removeCerts");

    P11ObjectIdentifier keyId = null;
    for (P11ObjectIdentifier m : identities.keySet()) {
      P11Identity identity = identities.get(m);
      if (objectId.equals(identity.getId().getCertId())) {
        keyId = m;
        break;
      }
    }

    if (keyId != null) {
      certificates.remove(objectId);
      identities.get(keyId).setCertificates(null);
    } else if (certificates.containsKey(objectId)) {
      certificates.remove(objectId);
    } else {
      throw new P11UnknownEntityException(slotId, objectId);
    }

    updateCaCertsOfIdentities();
    removeCerts0(objectId);
  }

  /**
   * Removes the key (private key, public key, secret key, and certificates) associated with
   * the given identifier {@code objectId}.
   *
   * @param identityId
   *          Identity identifier. Must not be {@code null}.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  public void removeIdentity(P11IdentityId identityId) throws P11TokenException {
    ParamUtil.requireNonNull("identityId", identityId);
    assertWritable("removeIdentity");
    P11ObjectIdentifier keyId = identityId.getKeyId();
    if (identities.containsKey(keyId)) {
      if (identityId.getCertId() != null) {
        certificates.remove(identityId.getCertId());
      }
      identities.get(keyId).setCertificates(null);
      identities.remove(keyId);
      updateCaCertsOfIdentities();
    }

    removeIdentity0(identityId);
  }

  /**
   * Removes the key (private key, public key, secret key, and certificates) associated with
   * the given identifier {@code objectId}.
   *
   * @param keyId
   *          Key identifier. Must not be {@code null}.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  public void removeIdentityByKeyId(P11ObjectIdentifier keyId) throws P11TokenException {
    ParamUtil.requireNonNull("keyId", keyId);
    assertWritable("removeIdentityByKeyId");

    P11IdentityId entityId = null;
    if (identities.containsKey(keyId)) {
      entityId = identities.get(keyId).getId();
      if (entityId.getCertId() != null) {
        certificates.remove(entityId.getCertId());
      }
      identities.get(keyId).setCertificates(null);
      identities.remove(keyId);
      updateCaCertsOfIdentities();

      removeIdentity0(entityId);
    }

  }

  /**
   * Adds the certificate to the PKCS#11 token under the given identifier {@code objectId}.
   *
   * @param cert
   *          Certificate to be added. Must not be {@code null}.
   * @param control
   *          Control of the object creation process. Must not be {@code null}.
   * @throws CertificateException
   *         if process with certificate fails.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  public P11ObjectIdentifier addCert(X509Certificate cert, P11NewObjectControl control)
      throws P11TokenException, CertificateException {
    ParamUtil.requireNonNull("cert", cert);
    ParamUtil.requireNonNull("control", control);
    assertWritable("addCert");

    if (control.getLabel() == null) {
      String cn = X509Util.getCommonName(cert.getSubjectX500Principal());
      control = new P11NewObjectControl(control.getId(), generateLabel(cn));
    }

    P11ObjectIdentifier objectId = addCert0(cert, control);
    certificates.put(objectId, new X509Cert(cert));
    updateCaCertsOfIdentities();
    LOG.info("added certificate {}", objectId);
    return objectId;
  }

  protected String generateLabel(String label) throws P11TokenException {

    String tmpLabel = label;
    int idx = 0;
    while (true) {
      boolean duplicated = false;
      for (P11ObjectIdentifier objectId : identities.keySet()) {
        P11IdentityId identityId = identities.get(objectId).getId();
        P11ObjectIdentifier pubKeyId = identityId.getPublicKeyId();
        P11ObjectIdentifier certId = identityId.getCertId();

        if (label.equals(objectId.getLabel())
            || (pubKeyId != null && label.equals(pubKeyId.getLabel())
            || (certId != null && label.equals(certId.getLabel())))) {
          duplicated = true;
          break;
        }
      }

      if (!duplicated) {
        for (P11ObjectIdentifier objectId : certificates.keySet()) {
          if (objectId.getLabel().equals(label)) {
            duplicated = true;
            break;
          }
        }
      }

      if (!duplicated) {
        return tmpLabel;
      }

      idx++;
      tmpLabel = label + "-" + idx;
    }
  }

  /**
   * Generates a secret key in the PKCS#11 token.
   *
   * @param keyType
   *          Key type
   * @param keysize
   *          Key size in bit
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the identity within the PKCS#11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  public P11IdentityId generateSecretKey(long keyType, int keysize, P11NewKeyControl control)
      throws P11TokenException {
    assertWritable("generateSecretKey");
    ParamUtil.requireNonNull("control", control);
    assertNoIdentityAndCert(control.getId(), control.getLabel());

    P11Identity identity = generateSecretKey0(keyType, keysize, control);
    addIdentity(identity);

    P11IdentityId id = identity.getId();
    LOG.info("generated secret key {}", id);
    return id;
  }

  /**
   * Imports secret key object in the PKCS#11 token. The key itself will not be generated
   * within the PKCS#11 token.
   *
   * @param keyType
   *          Key type
   * @param keyValue
   *          Key value. Must not be {@code null}.
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the key within the PKCS#11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  public P11ObjectIdentifier importSecretKey(long keyType, byte[] keyValue,
      P11NewKeyControl control) throws P11TokenException {
    ParamUtil.requireNonNull("control", control);
    assertWritable("createSecretKey");
    assertNoIdentityAndCert(control.getId(), control.getLabel());

    P11Identity identity = importSecretKey0(keyType, keyValue, control);
    addIdentity(identity);

    P11ObjectIdentifier objId = identity.getId().getKeyId();
    LOG.info("created secret key {}", objId);
    return objId;
  }

  /**
   * Generates an RSA keypair.
   *
   * @param keysize
   *          key size in bit
   * @param publicExponent
   *          RSA public exponent. Could be {@code null}.
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the identity within the PKCS#P11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  // CHECKSTYLE:SKIP
  public P11IdentityId generateRSAKeypair(int keysize, BigInteger publicExponent,
      P11NewKeyControl control) throws P11TokenException {
    ParamUtil.requireMin("keysize", keysize, 1024);
    if (keysize % 1024 != 0) {
      throw new IllegalArgumentException("key size is not multiple of 1024: " + keysize);
    }
    assertCanGenKeypair("generateRSAKeypair", PKCS11Constants.CKM_RSA_PKCS_KEY_PAIR_GEN, control);

    BigInteger tmpPublicExponent = publicExponent;
    if (tmpPublicExponent == null) {
      tmpPublicExponent = BigInteger.valueOf(65537);
    }

    P11Identity identity = generateRSAKeypair0(keysize, tmpPublicExponent, control);
    addIdentity(identity);
    P11IdentityId id = identity.getId();
    LOG.info("generated RSA keypair {}", id);
    return id;
  }

  /**
   * Generates a DSA keypair.
   *
   * @param plength
   *          bit length of P
   * @param qlength
   *          bit length of Q
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the identity within the PKCS#P11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  // CHECKSTYLE:SKIP
  public P11IdentityId generateDSAKeypair(int plength, int qlength, P11NewKeyControl control)
      throws P11TokenException {
    ParamUtil.requireMin("plength", plength, 1024);
    if (plength % 1024 != 0) {
      throw new IllegalArgumentException("key size is not multiple of 1024: " + plength);
    }
    assertCanGenKeypair("generateDSAKeypair", PKCS11Constants.CKM_DSA_KEY_PAIR_GEN, control);

    DSAParameterSpec dsaParams = DSAParameterCache.getDSAParameterSpec(plength, qlength, random);
    P11Identity identity = generateDSAKeypair0(dsaParams.getP(), dsaParams.getQ(), dsaParams.getG(),
        control);
    addIdentity(identity);
    P11IdentityId id = identity.getId();
    LOG.info("generated DSA keypair {}", id);
    return id;
  }

  /**
   * Generates a DSA keypair.
   *
   * @param p
   *          p of DSA. Must not be {@code null}.
   * @param q
   *          q of DSA. Must not be {@code null}.
   * @param g
   *          g of DSA. Must not be {@code null}.
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the identity within the PKCS#P11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  // CHECKSTYLE:SKIP
  public P11IdentityId generateDSAKeypair(BigInteger p, BigInteger q, BigInteger g,
      P11NewKeyControl control) throws P11TokenException {
    ParamUtil.requireNonNull("p", p);
    ParamUtil.requireNonNull("q", q);
    ParamUtil.requireNonNull("g", g);
    assertCanGenKeypair("generateDSAKeypair", PKCS11Constants.CKM_DSA_KEY_PAIR_GEN, control);

    P11Identity identity = generateDSAKeypair0(p, q, g, control);
    addIdentity(identity);
    P11IdentityId id = identity.getId();
    LOG.info("generated DSA keypair {}", id);
    return id;
  }

  /**
   * Generates an EC keypair.
   *
   * @param curveNameOrOid
   *         Object identifier or name of the EC curve. Must not be {@code null}.
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the identity within the PKCS#P11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  // CHECKSTYLE:SKIP
  public P11IdentityId generateECKeypair(String curveNameOrOid, P11NewKeyControl control)
      throws P11TokenException {
    ParamUtil.requireNonBlank("curveNameOrOid", curveNameOrOid);
    assertCanGenKeypair("generateECKeypair", PKCS11Constants.CKM_EC_KEY_PAIR_GEN, control);

    ASN1ObjectIdentifier curveId = AlgorithmUtil.getCurveOidForCurveNameOrOid(curveNameOrOid);
    if (curveId == null) {
      throw new IllegalArgumentException("unknown curve " + curveNameOrOid);
    }
    P11Identity identity = generateECKeypair0(curveId, control);
    addIdentity(identity);
    P11IdentityId id = identity.getId();
    LOG.info("generated EC keypair {}", id);
    return id;

  }

  /**
   * Generates an SM2 keypair.
   *
   * @param control
   *          Control of the key generation process. Must not be {@code null}.
   * @return the identifier of the identity within the PKCS#P11 token.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  // CHECKSTYLE:SKIP
  public P11IdentityId generateSM2Keypair(P11NewKeyControl control) throws P11TokenException {
    assertCanGenKeypair("generateSM2Keypair", PKCS11Constants.CKM_VENDOR_SM2_KEY_PAIR_GEN, control);

    P11Identity identity = generateSM2Keypair0(control);
    addIdentity(identity);
    P11IdentityId id = identity.getId();
    LOG.info("generated SM2 keypair {}", id);
    return id;
  }

  private void assertCanGenKeypair(String methodName, long mechanism, P11NewKeyControl control)
      throws P11UnsupportedMechanismException, P11PermissionException, P11DuplicateEntityException {
    ParamUtil.requireNonNull("control", control);
    assertWritable(methodName);
    assertMechanismSupported(mechanism);
    assertNoIdentityAndCert(control.getId(), control.getLabel());
  }

  /**
   * Updates the certificate associated with the given ID {@code keyId} with the given certificate
   * {@code newCert}.
   *
   * @param keyId
   *          Object identifier of the private key. Must not be {@code null}.
   * @param newCert
   *          Certificate to be added. Must not be {@code null}.
   * @throws CertificateException
   *         if process with certificate fails.
   * @throws P11TokenException
   *         if PKCS#11 token exception occurs.
   */
  public void updateCertificate(P11ObjectIdentifier keyId, X509Certificate newCert)
      throws P11TokenException, CertificateException {
    ParamUtil.requireNonNull("keyId", keyId);
    ParamUtil.requireNonNull("newCert", newCert);
    assertWritable("updateCertificate");

    P11Identity identity = identities.get(keyId);
    if (identity == null) {
      throw new P11UnknownEntityException("could not find private key " + keyId);
    }

    java.security.PublicKey pk = identity.getPublicKey();
    java.security.PublicKey newPk = newCert.getPublicKey();
    if (!pk.equals(newPk)) {
      throw new P11TokenException("the given certificate is not for key " + keyId);
    }

    updateCertificate0(keyId, newCert);
    identity.setCertificates(new X509Certificate[]{newCert});
    updateCaCertsOfIdentities();
    LOG.info("updated certificate for key {}", keyId);
  }

  /**
   * Writes the token details to the given {@code stream}.
   * @param stream
   *          Output stream. Must not be {@code null}.
   * @param verbose
   *          Whether to show the details verbosely.
   * @throws IOException
   *         if IO error occurs.
   */
  public void showDetails(OutputStream stream, boolean verbose) throws IOException {
    ParamUtil.requireNonNull("stream", stream);

    List<P11ObjectIdentifier> sortedKeyIds = getSortedObjectIds(identities.keySet());
    int size = sortedKeyIds.size();

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < size; i++) {
      P11ObjectIdentifier keyId = sortedKeyIds.get(i);
      P11Identity identity = identities.get(keyId);

      String label = keyId.getLabel();
      sb.append("\t").append(i + 1).append(". ").append(label);
      sb.append(" (").append("id: ").append(keyId.getIdHex());
      P11IdentityId identityId = identity.getId();
      P11ObjectIdentifier certId = identityId.getCertId();
      if (certId != null && !certId.equals(keyId)) {
        sb.append(", certificate label: ").append(identityId.getCertId().getLabel());
      }

      P11ObjectIdentifier pubKeyId = identityId.getPublicKeyId();
      if (pubKeyId != null && !pubKeyId.equals(keyId)) {
        sb.append(", publicKey label: ").append(pubKeyId.getLabel());
      }

      sb.append(")\n");

      if (identity.getPublicKey() != null) {

        String algo = identity.getPublicKey().getAlgorithm();
        sb.append("\t\tAlgorithm: ").append(algo).append("\n");
        X509Certificate[] certs = identity.certificateChain();
        if (certs == null || certs.length == 0) {
          sb.append("\t\tCertificate: NONE\n");
        } else {
          for (int j = 0; j < certs.length; j++) {
            formatString(j, verbose, sb, certs[j]);
          }
        }
      } else {
        sb.append("\t\tSymmetric key\n");
      }
    }

    sortedKeyIds.clear();
    for (P11ObjectIdentifier objectId : certificates.keySet()) {
      if (!identities.containsKey(objectId)) {
        sortedKeyIds.add(objectId);
      }
    }

    Collections.sort(sortedKeyIds);

    if (!sortedKeyIds.isEmpty()) {
      Collections.sort(sortedKeyIds);
      size = sortedKeyIds.size();
      for (int i = 0; i < size; i++) {
        P11ObjectIdentifier objectId = sortedKeyIds.get(i);
        sb.append("\tCert-").append(i + 1).append(". ").append(objectId.getLabel());
        sb.append(" (").append("id: ").append(objectId.getLabel()).append(")\n");
        formatString(null, verbose, sb, certificates.get(objectId).getCert());
      }
    }

    if (sb.length() > 0) {
      stream.write(sb.toString().getBytes());
    }
  }

  protected void assertWritable(String operationName) throws P11PermissionException {
    if (readOnly) {
      throw new P11PermissionException("Writable operation " + operationName + " is not permitted");
    }
  }

  protected boolean existsIdentityForId(byte[] id) {
    for (P11ObjectIdentifier objectId : identities.keySet()) {
      if (objectId.matchesId(id)) {
        return true;
      }
    }

    return false;
  }

  protected boolean existsCertForId(byte[] id) {
    for (P11ObjectIdentifier objectId : certificates.keySet()) {
      if (objectId.matchesId(id)) {
        return true;
      }
    }

    return false;
  }

  private static void formatString(Integer index, boolean verbose, StringBuilder sb,
      X509Certificate cert) {
    String subject = X509Util.getRfc4519Name(cert.getSubjectX500Principal());
    sb.append("\t\tCertificate");
    if (index != null) {
      sb.append("[").append(index).append("]");
    }
    sb.append(": ");

    if (!verbose) {
      sb.append(subject).append("\n");
      return;
    }

    sb.append("\n\t\t\tSubject: ").append(subject);

    String issuer = X509Util.getRfc4519Name(cert.getIssuerX500Principal());
    sb.append("\n\t\t\tIssuer: ").append(issuer);
    sb.append("\n\t\t\tSerial: ").append(LogUtil.formatCsn(cert.getSerialNumber()));
    sb.append("\n\t\t\tStart time: ").append(cert.getNotBefore());
    sb.append("\n\t\t\tEnd time: ").append(cert.getNotAfter());
    sb.append("\n\t\t\tSHA1 Sum: ");
    try {
      sb.append(HashAlgo.SHA1.hexHash(cert.getEncoded()));
    } catch (CertificateEncodingException ex) {
      sb.append("ERROR");
    }
    sb.append("\n");
  }

  private List<P11ObjectIdentifier> getSortedObjectIds(Set<P11ObjectIdentifier> sets) {
    List<P11ObjectIdentifier> ids = new ArrayList<>(sets);
    Collections.sort(ids);
    return ids;
  }

}