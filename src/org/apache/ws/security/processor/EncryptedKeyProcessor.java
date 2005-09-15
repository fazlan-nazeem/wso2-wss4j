/*
 * Copyright  2003-2004 The Apache Software Foundation.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ws.security.processor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSDocInfo;
import org.apache.ws.security.WSPasswordCallback;
import org.apache.ws.security.WSSecurityEngine;
import org.apache.ws.security.WSSecurityEngineResult;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.message.token.SecurityTokenReference;
import org.apache.ws.security.message.token.X509Security;
import org.apache.ws.security.util.Base64;
import org.apache.ws.security.util.WSSecurityUtil;
import org.apache.xml.security.encryption.XMLCipher;
import org.apache.xml.security.encryption.XMLEncryptionException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.xml.namespace.QName;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Vector;

public class EncryptedKeyProcessor implements Processor {
    private static Log log = LogFactory.getLog(EncryptedKeyProcessor.class.getName());
    private static Log tlog =
            LogFactory.getLog("org.apache.ws.security.TIME");

    byte[] decryptedBytes = null;

    public void handleToken(Element elem, Crypto crypto, Crypto decCrypto, CallbackHandler cb, WSDocInfo wsDocInfo, Vector returnResults) throws WSSecurityException {
        if (log.isDebugEnabled()) {
            log.debug("Found encrypted key element");
        }
        if (decCrypto == null) {
            throw new WSSecurityException(WSSecurityException.FAILURE,
                    "noDecCryptoFile");
        }
        if (cb == null) {
            throw new WSSecurityException(WSSecurityException.FAILURE,
                    "noCallback");
        }
        handleEncryptedKey((Element) elem, cb, decCrypto);
        returnResults.add(0, new WSSecurityEngineResult(WSConstants.ENCR, null, null, null, null));
    }

    public void handleEncryptedKey(Element xencEncryptedKey,
                                   CallbackHandler cb, Crypto crypto) throws WSSecurityException {
        handleEncryptedKey(xencEncryptedKey, cb, crypto, null);
    }

    public void handleEncryptedKey(Element xencEncryptedKey,
                                   PrivateKey privatekey) throws WSSecurityException {
        handleEncryptedKey(xencEncryptedKey, null, null, privatekey);
    }

    public void handleEncryptedKey(Element xencEncryptedKey,
                                   CallbackHandler cb, Crypto crypto, PrivateKey privateKey)
            throws WSSecurityException {
        long t0 = 0, t1 = 0, t2 = 0;
        if (tlog.isDebugEnabled()) {
            t0 = System.currentTimeMillis();
        }
        // need to have it to find the encryped data elements in the envelope
        Document doc = xencEncryptedKey.getOwnerDocument();

        // lookup xenc:EncryptionMethod, get the Algorithm attribute to determine
        // how the key was encrypted. Then check if we support the algorithm

        Node tmpE = null;    // short living Element used for lookups only
        tmpE = (Element) WSSecurityUtil.getDirectChild((Node) xencEncryptedKey,
                "EncryptionMethod", WSConstants.ENC_NS);
        String keyEncAlgo = null;
        if (tmpE != null) {
            keyEncAlgo = ((Element) tmpE).getAttribute("Algorithm");
        }
        if (keyEncAlgo == null) {
            throw new WSSecurityException
                    (WSSecurityException.UNSUPPORTED_ALGORITHM, "noEncAlgo");
        }
        Cipher cipher = WSSecurityUtil.getCipherInstance(keyEncAlgo);
        /*
         * Well, we can decrypt the session (symmetric) key. Now lookup CipherValue, this is the value of the
         * encrypted session key (session key usually is a symmetrical key that encrypts
         * the referenced content). This is a 2-step lookup
         */
        Element xencCipherValue = null;
        tmpE = (Element) WSSecurityUtil.getDirectChild((Node) xencEncryptedKey, "CipherData", WSConstants.ENC_NS);
        if (tmpE != null) {
            xencCipherValue = (Element) WSSecurityUtil.getDirectChild((Node) tmpE,
                    "CipherValue", WSConstants.ENC_NS);
        }
        if (xencCipherValue == null) {
            throw new WSSecurityException
                    (WSSecurityException.INVALID_SECURITY, "noCipher");
        }

        if (privateKey == null) {
            Element keyInfo = (Element) WSSecurityUtil.getDirectChild((Node) xencEncryptedKey,
                    "KeyInfo", WSConstants.SIG_NS);
            String alias;
            if (keyInfo != null) {
                Element secRefToken;
                secRefToken = (Element) WSSecurityUtil.getDirectChild(keyInfo,
                        "SecurityTokenReference", WSConstants.WSSE_NS);
                if (secRefToken == null) {
                    secRefToken = (Element) WSSecurityUtil.getDirectChild(keyInfo,
                            "KeyName", WSConstants.SIG_NS);
                }
                if (secRefToken == null) {
                    throw new WSSecurityException
                            (WSSecurityException.INVALID_SECURITY, "noSecTokRef");
                }
                SecurityTokenReference secRef = new SecurityTokenReference(secRefToken);
                /*
				 * Well, at this point there are several ways to get the key.
				 * Try to handle all of them :-).
				 */
                alias = null;
                /*
                * handle X509IssuerSerial here. First check if all elements are available,
                * get the appropriate data, check if all data is available.
                * If all is ok up to that point, look up the certificate alias according
                * to issuer name and serial number.
                * This method is recommended by OASIS WS-S specification, X509 profile
                */
                if (secRef.containsX509Data() || secRef.containsX509IssuerSerial()) {
                    alias = secRef.getX509IssuerSerialAlias(crypto);
                    if (log.isDebugEnabled()) {
                        log.debug("X509IssuerSerial alias: " + alias);
                    }
                }
                /*
                * If wsse:KeyIdentifier found, then the public key of the attached cert was used to
                * encrypt the session (symmetric) key that encrypts the data. Extract the certificate
                * using the BinarySecurity token (was enhanced to handle KeyIdentifier too).
                * This method is _not_ recommended by OASIS WS-S specification, X509 profile
                */
                else if (secRef.containsKeyIdentifier()) {
                    X509Certificate[] certs = secRef.getKeyIdentifier(crypto);
                    if (certs == null || certs.length < 1 || certs[0] == null) {
                        throw new WSSecurityException(WSSecurityException.FAILURE,
                                "invalidX509Data", new Object[]{"for decryption (KeyId)"});
                    }
                    /*
                    * Here we have the certificate. Now find the alias for it. Needed to identify
                    * the private key associated with this certificate
                    */
                    alias = crypto.getAliasForX509Cert(certs[0]);
                    if (log.isDebugEnabled()) {
                        log.debug("cert: " + certs[0]);
                        log.debug("KeyIdentifier Alias: " + alias);
                    }
                } else if (secRef.containsReference()) {
                    Element bstElement = secRef.getTokenElement(doc, null);

                    // at this point ... check token type: Binary
                    QName el =
                            new QName(bstElement.getNamespaceURI(),
                                    bstElement.getLocalName());
                    if (el.equals(WSSecurityEngine.binaryToken)) {
                        X509Security token = null;
                        String value = bstElement.getAttribute(WSSecurityEngine.VALUE_TYPE);
                        if (!X509Security.getType().equals(value)
                                || ((token = new X509Security(bstElement)) == null)) {
                            throw new WSSecurityException(WSSecurityException.UNSUPPORTED_SECURITY_TOKEN,
                                    "unsupportedBinaryTokenType",
                                    new Object[]{"for decryption (BST)"});
                        }
                        X509Certificate cert = token.getX509Certificate(crypto);
                        if (cert == null) {
                            throw new WSSecurityException(WSSecurityException.FAILURE,
                                    "invalidX509Data",
                                    new Object[]{"for decryption"});
                        }
                        /*
                        * Here we have the certificate. Now find the alias for it. Needed to identify
                        * the private key associated with this certificate
                        */
                        alias = crypto.getAliasForX509Cert(cert);
                        if (log.isDebugEnabled()) {
                            log.debug("BST Alias: " + alias);
                        }
                    } else {
                        throw new WSSecurityException(WSSecurityException.INVALID_SECURITY,
                                "unsupportedToken",
                                null);
                    }
                } else if (secRef.containsKeyName()) {
                    alias = crypto.getAliasForX509Cert(secRef.getKeyNameValue());
                    if (log.isDebugEnabled()) {
                        log.debug("KeyName alias: " + alias);
                    }
                } else {
                    throw new WSSecurityException(WSSecurityException.FAILURE, "unsupportedKeyId");
                }
            } else if (crypto.getDefaultX509Alias() != null) {
                alias = crypto.getDefaultX509Alias();
            } else {
                throw new WSSecurityException
                        (WSSecurityException.INVALID_SECURITY, "noKeyinfo");
            }
            /*
            * At this point we have all information necessary to decrypt the session
            * key:
            * - the Cipher object intialized with the correct methods
            * - The data that holds the encrypted session key
            * - the alias name for the private key
            *
            * Now use the callback here to get password that enables
            * us to read the private key
            */
            WSPasswordCallback pwCb = new WSPasswordCallback(alias, WSPasswordCallback.DECRYPT);
            Callback[] callbacks = new Callback[1];
            callbacks[0] = pwCb;
            try {
                cb.handle(callbacks);
            } catch (IOException e) {
                throw new WSSecurityException(WSSecurityException.FAILURE,
                        "noPassword",
                        new Object[]{alias});
            } catch (UnsupportedCallbackException e) {
                throw new WSSecurityException(WSSecurityException.FAILURE,
                        "noPassword",
                        new Object[]{alias});
            }
            String password = pwCb.getPassword();
            if (password == null) {
                throw new WSSecurityException(WSSecurityException.FAILURE,
                        "noPassword", new Object[]{alias});
            }

            try {
                privateKey = crypto.getPrivateKey(alias, password);
            } catch (Exception e) {
                throw new WSSecurityException(WSSecurityException.FAILED_ENC_DEC, null, null, e);
            }
        }

        try {
            cipher.init(Cipher.DECRYPT_MODE,
                    privateKey);
        } catch (Exception e1) {
            throw new WSSecurityException(WSSecurityException.FAILED_ENC_DEC, null, null, e1);
        }

        try {
            decryptedBytes =
                    cipher.doFinal(getDecodedBase64EncodedData(xencCipherValue));
        } catch (IllegalStateException e2) {
            throw new WSSecurityException(WSSecurityException.FAILED_ENC_DEC, null, null, e2);
        } catch (IllegalBlockSizeException e2) {
            throw new WSSecurityException(WSSecurityException.FAILED_ENC_DEC, null, null, e2);
        } catch (BadPaddingException e2) {
            throw new WSSecurityException(WSSecurityException.FAILED_ENC_DEC, null, null, e2);
        }

        if (tlog.isDebugEnabled()) {
            t1 = System.currentTimeMillis();
        }

        /* At this point we have the decrypted session (symmetric) key. According
         * to W3C XML-Enc this key is used to decrypt _any_ references contained in
         * the reference list
         * Now lookup the references that are encrypted with this key
         */
        String dataRefURI = null;
        Element refList = (Element) WSSecurityUtil.getDirectChild((Node) xencEncryptedKey,
                "ReferenceList", WSConstants.ENC_NS);
        if (refList != null) {
            for (tmpE = refList.getFirstChild();
                 tmpE != null; tmpE = tmpE.getNextSibling()) {
                if (tmpE.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                if (!tmpE.getNamespaceURI().equals(WSConstants.ENC_NS)) {
                    continue;
                }
                if (tmpE.getLocalName().equals("DataReference")) {
                    dataRefURI = ((Element) tmpE).getAttribute("URI");
                    decryptDataRef(doc, dataRefURI, decryptedBytes);
                }
            }
        }

        if (tlog.isDebugEnabled()) {
            t2 = System.currentTimeMillis();
            tlog.debug("XMLDecrypt: total= " + (t2 - t0) +
                    ", get-sym-key= " + (t1 - t0) +
                    ", decrypt= " + (t2 - t1));
        }
        return;
    }

    /**
     * Method getDecodedBase64EncodedData
     *
     * @param element
     * @return a byte array containing the decoded data
     * @throws WSSecurityException
     */
    public static byte[] getDecodedBase64EncodedData(Element element) throws WSSecurityException {
        StringBuffer sb = new StringBuffer();
        NodeList children = element.getChildNodes();
        int iMax = children.getLength();
        for (int i = 0; i < iMax; i++) {
            Node curr = children.item(i);
            if (curr.getNodeType() == Node.TEXT_NODE)
                sb.append(((Text) curr).getData());
        }
        String encodedData = sb.toString();
        return Base64.decode(encodedData);
    }

    private void decryptDataRef(Document doc, String dataRefURI, byte[] decryptedData) throws WSSecurityException {
        if (log.isDebugEnabled()) {
            log.debug("found data refernce: " + dataRefURI);
        }
        /*
         * Look up the encrypted data. First try wsu:Id="someURI". If no such Id then
         * try the generic lookup to find Id="someURI"
         */
        Element encBodyData = null;
        if ((encBodyData = WSSecurityUtil.getElementByWsuId(doc, dataRefURI)) == null) {
            encBodyData = WSSecurityUtil.getElementByGenId(doc, dataRefURI);
        }
        if (encBodyData == null) {
            throw new WSSecurityException
                    (WSSecurityException.INVALID_SECURITY,
                            "dataRef", new Object[]{dataRefURI});
        }

        boolean content = X509Util.isContent(encBodyData);

        // get the encryprion method
        String symEncAlgo = X509Util.getEncAlgo(encBodyData);

        SecretKey symmetricKey = WSSecurityUtil.prepareSecretKey(
                symEncAlgo, decryptedData);

        // initialize Cipher ....
        XMLCipher xmlCipher = null;
        try {
            xmlCipher = XMLCipher.getInstance(symEncAlgo);
            xmlCipher.init(XMLCipher.DECRYPT_MODE, symmetricKey);
        } catch (XMLEncryptionException e) {
            throw new WSSecurityException(WSSecurityException.UNSUPPORTED_ALGORITHM, null, null, e);
        }

        if (content) {
            encBodyData = (Element) encBodyData.getParentNode();
        }
        try {
            xmlCipher.doFinal(doc, encBodyData, content);
        } catch (Exception e1) {
            throw new WSSecurityException(WSSecurityException.FAILED_ENC_DEC, null, null, e1);
        }
    }

    public byte[] getDecryptedBytes() {
        return decryptedBytes;
    }
}