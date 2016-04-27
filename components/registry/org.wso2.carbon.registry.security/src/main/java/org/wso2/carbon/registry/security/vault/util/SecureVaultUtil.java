/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.registry.security.vault.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Properties;

import org.apache.axis2.AxisFault;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.core.util.CryptoException;
import org.wso2.carbon.core.util.CryptoUtil;
import org.wso2.carbon.registry.core.Collection;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.registry.security.vault.CipherInitializer;
import org.wso2.carbon.registry.security.vault.internal.SecurityServiceHolder;
import sun.misc.BASE64Encoder;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

public class SecureVaultUtil {

    private static Log log = LogFactory.getLog(SecureVaultUtil.class);

    public static Properties loadProperties() {
        Properties properties = new Properties();
        String carbonHome = System.getProperty(SecureVaultConstants.CARBON_HOME);
        String filePath = carbonHome + File.separator + SecureVaultConstants.REPOSITORY_DIR +
                          File.separator + SecureVaultConstants.CONF_DIR + File.separator +
                          SecureVaultConstants.SECURITY_DIR + File.separator +
                          SecureVaultConstants.SECRET_CONF;

        File dataSourceFile = new File(filePath);
        if (!dataSourceFile.exists()) {
            return properties;
        }

        InputStream in = null;
        try {
            in = new FileInputStream(dataSourceFile);
            properties.load(in);
        } catch (IOException e) {
            String msg = "Error loading properties from a file at :" + filePath;
            log.warn(msg, e);
            return properties;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {

                }
            }
        }
        return properties;
    }

    public static String encryptValue(String plainTextPass) throws AxisFault {
        CipherInitializer ciperInitializer = CipherInitializer.getInstance();
        byte[] plainTextPassByte = plainTextPass.getBytes();

        try {
            Cipher cipher = ciperInitializer.getEncryptionProvider();
            if (cipher == null) {
                if (cipher == null) {
                    log.error("Either Configuration properties can not be loaded or No secret"
                              + " repositories have been configured please check PRODUCT_HOME/repository/conf/security "
                              + " refer links related to configure WSO2 Secure vault");
                    handleException(log, "Failed to load security key store information ,"
                                                                      +
                                                                      "Configure secret-conf.properties properly by referring to https://docs.wso2.com/display/Carbon440/Encrypting+Passwords+with+Cipher+Tool",
                                                                 null);
                }
            }
            byte[] encryptedPassword = cipher.doFinal(plainTextPassByte);
            BASE64Encoder encoder = new BASE64Encoder();
            String encodedValue = encoder.encode(encryptedPassword);
            return encodedValue;
        } catch (IllegalBlockSizeException e) {
            handleException(log, "Error encrypting password ", e);
        } catch (BadPaddingException e) {
            handleException(log, "Error encrypting password ", e);
        }

        return null;
    }

    private static void handleException(Log log, String message, Exception e) throws AxisFault {

        if (e == null) {

            AxisFault exception = new AxisFault(message);
            log.error(message, exception);
            throw exception;

        } else {
            message = message + " :: " + e.getMessage();
            log.error(message, e);
            throw new AxisFault(message, e);
        }
    }

    public static void createRegistryResource(int tenantId) throws RegistryException {
        try {
            UserRegistry registry;
            if (tenantId != -1234){
                registry = SecurityServiceHolder.getInstance().getRegistryService().getConfigSystemRegistry(tenantId);
            } else {
                registry = SecurityServiceHolder.getInstance().getRegistryService().getConfigSystemRegistry();
            }
            // creating vault-specific storage repository (this happens only if
            // not resource not existing)
            if (!registry.resourceExists(SecureVaultConstants.ENCRYPTED_PROPERTY_STORAGE_PATH)) {
                Collection secureVaultCollection = registry.newCollection();
                registry.put(SecureVaultConstants.ENCRYPTED_PROPERTY_STORAGE_PATH, secureVaultCollection);
            }
        } catch (RegistryException e) {
            throw new RegistryException("Error while intializing the registry");
        }
    }

    /**
     * Method to do the encryption operation.
     *
     * @param plainTextPass		plain text value.
     * @return				encrypted value.
     * @throws RegistryException	Throws when an error occurs during encryption.
     */
    public static String doEncrypt(String plainTextPass) throws CryptoException {
        CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
        return cryptoUtil.encryptAndBase64Encode(plainTextPass.getBytes(Charset.forName("UTF-8")));
    }

    /**
     * Method to decrypt a property, when key of the property is provided.
     *
     * @param key			key of the property.
     * @return 				decrypted property value.
     * @throws RegistryException	Throws when an error occurs during decryption.
     */
    public static String getDecryptedPropertyValue(String key) throws RegistryException {
        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        UserRegistry registry = SecurityServiceHolder.getInstance().getRegistryService()
                .getConfigSystemRegistry(tenantId);

        if (registry.resourceExists(SecureVaultConstants.ENCRYPTED_PROPERTY_STORAGE_PATH)) {
            Resource registryResource = registry.get(SecureVaultConstants.ENCRYPTED_PROPERTY_STORAGE_PATH);
            String propertyValue = registryResource.getProperty(key);
            if (propertyValue != null) {
                try {
                    return doDecrypt(propertyValue);
                } catch (CryptoException | UnsupportedEncodingException e) {
                    throw new RegistryException("Error while decrypting the property value", e);
                }
            } else {
                throw new RegistryException("Property does not exist with key \"" + key + "\" at path " +
                        SecureVaultConstants.ENCRYPTED_PROPERTY_CONFIG_REGISTRY_PATH);
            }
        } else {
            throw new RegistryException("Collection does not exist at path "
                    + SecureVaultConstants.ENCRYPTED_PROPERTY_CONFIG_REGISTRY_PATH);
        }
    }

    /**
     * Method to decrypt a property, when encrypted value is provided.
     *
     * @param encryptedValue                	encrypted property value.
     * @return 					decrypted value.
     * @throws CryptoException              	Throws when an error occurs during decryption.
     * @throws UnsupportedEncodingException	Throws when an error occurs during byte array to string conversion.
     */
    public static String doDecrypt(String encryptedValue) throws CryptoException, UnsupportedEncodingException {
        CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
        byte[] decryptedBytes = cryptoUtil.base64DecodeAndDecrypt(encryptedValue);
        return new String(decryptedBytes, "UTF-8");
    }
}
