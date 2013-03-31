/**
 * Copyright 2012 multibit.org
 *
 * Licensed under the MIT license (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://opensource.org/licenses/mit-license.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multibit.viewsystem.swing.action;

import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JPasswordField;
import javax.swing.SwingWorker;

import org.bitcoinj.wallet.Protos.Wallet.EncryptionType;
import org.multibit.controller.MultiBitController;
import org.multibit.file.PrivateKeyAndDate;
import org.multibit.file.PrivateKeysHandler;
import org.multibit.file.PrivateKeysHandlerException;
import org.multibit.file.WalletSaveException;
import org.multibit.message.Message;
import org.multibit.message.MessageManager;
import org.multibit.model.PerWalletModelData;
import org.multibit.model.WalletBusyListener;
import org.multibit.network.ReplayManager;
import org.multibit.network.ReplayTask;
import org.multibit.utils.DateUtils;
import org.multibit.viewsystem.swing.MultiBitFrame;
import org.multibit.viewsystem.swing.view.panels.ImportPrivateKeysPanel;
import org.multibit.viewsystem.swing.view.walletlist.SingleWalletPanel;
import org.multibit.viewsystem.swing.view.walletlist.WalletListPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.crypto.KeyCrypter;
import com.google.bitcoin.crypto.KeyCrypterException;
import com.piuk.blockchain.MyWallet;

/**
 * This {@link Action} imports the private keys to the active wallet.
 */
public class ImportPrivateKeysSubmitAction extends MultiBitSubmitAction implements WalletBusyListener {

    private static final Logger log = LoggerFactory.getLogger(ImportPrivateKeysSubmitAction.class);

    private static final long serialVersionUID = 1923492087598757765L;

    private MultiBitFrame mainFrame;
    private ImportPrivateKeysPanel importPrivateKeysPanel;
    private JPasswordField walletPasswordField;
    private JPasswordField passwordField;
    private JPasswordField passwordField2;

    private boolean performReplay = true;

    private File privateKeysBackupFile;

    private static final long NUMBER_OF_MILLISECONDS_IN_A_SECOND = 1000;

    /**
     * Creates a new {@link ImportPrivateKeysSubmitAction}.
     */
    public ImportPrivateKeysSubmitAction(MultiBitController controller, MultiBitFrame mainFrame, ImportPrivateKeysPanel importPrivateKeysPanel,
            ImageIcon icon, JPasswordField walletPasswordField, JPasswordField passwordField1, JPasswordField passwordField2) {
        super(controller, "importPrivateKeysSubmitAction.text", "importPrivateKeysSubmitAction.tooltip",
                "importPrivateKeysSubmitAction.mnemonicKey", icon);
        this.mainFrame = mainFrame;
        this.importPrivateKeysPanel = importPrivateKeysPanel;
        this.walletPasswordField = walletPasswordField;
        this.passwordField = passwordField1;
        this.passwordField2 = passwordField2;
        
        // This action is a WalletBusyListener.
        controller.registerWalletBusyListener(this);
        walletBusyChange(controller.getModel().getActivePerWalletModelData().isBusy());
    }

    /**
     * Import the private keys and replay the blockchain.
     */
    @Override
    public void actionPerformed(ActionEvent event) {
        privateKeysBackupFile = null;

        if (abort()) {
            return;
        }

        String importFilename = importPrivateKeysPanel.getOutputFilename();
        if (importFilename == null || importFilename.equals("")) {
            // No import file - nothing to do.
            importPrivateKeysPanel.setMessageText1(controller.getLocaliser().getString(
                    "importPrivateKeysSubmitAction.privateKeysNothingToDo"));
            importPrivateKeysPanel.setMessageText2(" ");
            return;
        }

        // See if a wallet password is required and present.
        if (controller.getModel().getActiveWallet() != null) {
            KeyCrypter keyCrypter = controller.getModel().getActiveWallet().getKeyCrypter();
            if (keyCrypter != null && keyCrypter.getUnderstoodEncryptionType() == EncryptionType.ENCRYPTED_SCRYPT_AES) {
                if (walletPasswordField.getPassword() == null || walletPasswordField.getPassword().length == 0) {
                    importPrivateKeysPanel.setMessageText1(controller.getLocaliser().getString(
                            "showExportPrivateKeysAction.youMustEnterTheWalletPassword"));
                    importPrivateKeysPanel.setMessageText2(" ");
                    return;
                }

                try {
                    // See if the password is the correct wallet password.
                    if (!controller.getModel().getActiveWallet()
                            .checkPassword(CharBuffer.wrap(walletPasswordField.getPassword()))) {
                        // The password supplied is incorrect.
                        importPrivateKeysPanel.setMessageText1(controller.getLocaliser().getString(
                                "createNewReceivingAddressSubmitAction.passwordIsIncorrect"));
                        importPrivateKeysPanel.setMessageText2(" ");
                        return;
                    }
                } catch (KeyCrypterException ede) {
                    log.debug(ede.getClass().getCanonicalName() + " " + ede.getMessage());
                    // The password supplied is probably incorrect.
                    importPrivateKeysPanel.setMessageText1(controller.getLocaliser().getString(
                            "createNewReceivingAddressSubmitAction.passwordIsIncorrect"));
                    importPrivateKeysPanel.setMessageText2(" ");
                    return;
                }
            }
        }

        setEnabled(false);

        File importFile = new File(importFilename);

        CharSequence passwordCharSequence = CharBuffer.wrap(passwordField.getPassword());

        try {
            if (importPrivateKeysPanel.multiBitFileChooser.accept(importFile)) {

                PrivateKeysHandler privateKeysHandler = new PrivateKeysHandler(controller.getModel().getNetworkParameters());
                importPrivateKeysPanel.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                Collection<PrivateKeyAndDate> privateKeyAndDateArray = privateKeysHandler.readInPrivateKeys(importFile,
                        passwordCharSequence);

                changeWalletBusyAndImportInBackground(privateKeyAndDateArray,  CharBuffer.wrap(walletPasswordField.getPassword()));
                importPrivateKeysPanel.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            } else if (importPrivateKeysPanel.myWalletEncryptedFileChooser.accept(importFile)) {
                String importFileContents = PrivateKeysHandler.readFile(importFile);

                String mainPassword = new String(passwordField.getPassword());
                String secondPassword = new String(passwordField2.getPassword());

                MyWallet wallet = new MyWallet(importFileContents, mainPassword);

                boolean needSecondPassword = false;
                if (wallet.isDoubleEncrypted()) {
                    if ("".equals(secondPassword)) {
                        needSecondPassword = true;
                        importPrivateKeysPanel.requestSecondPassword();
                    }
                }

                if (!needSecondPassword) {
                    wallet.setTemporySecondPassword(secondPassword);

                    Wallet bitcoinj = wallet.getBitcoinJWallet();
                    Collection<PrivateKeyAndDate> privateKeyAndDateArray = new ArrayList<PrivateKeyAndDate>();
                    for (ECKey key : bitcoinj.keychain) {
                        privateKeyAndDateArray.add(new PrivateKeyAndDate(key, null));
                    }
                    changeWalletBusyAndImportInBackground(privateKeyAndDateArray, CharBuffer.wrap(walletPasswordField.getPassword()));
                }

            } else if (importPrivateKeysPanel.myWalletPlainFileChooser.accept(importFile)) {
                String importFileContents = PrivateKeysHandler.readFile(importFile);

                MyWallet wallet = new MyWallet(importFileContents);

                Wallet bitcoinj = wallet.getBitcoinJWallet();
                Collection<PrivateKeyAndDate> privateKeyAndDateArray = new ArrayList<PrivateKeyAndDate>();
                for (ECKey key : bitcoinj.keychain) {
                    privateKeyAndDateArray.add(new PrivateKeyAndDate(key, null));
                }
                changeWalletBusyAndImportInBackground(privateKeyAndDateArray, CharBuffer.wrap(walletPasswordField.getPassword()));

            }
        } catch (Exception e) {
            log.error(e.getClass().getName() + " " + e.getMessage());
            setEnabled(true);

            importPrivateKeysPanel.setMessageText1(controller.getLocaliser().getString(
                    "importPrivateKeysSubmitAction.privateKeysUnlockFailure", new Object[] { e.getMessage() }));
            importPrivateKeysPanel.setMessageText2(" ");
            return;
        } finally {
            importPrivateKeysPanel.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        }
    }

    private void changeWalletBusyAndImportInBackground(final Collection<PrivateKeyAndDate> privateKeyAndDateArray,
            final CharSequence walletPassword) {
        // Double check wallet is not busy then declare that the active wallet
        // is busy with the task
        PerWalletModelData perWalletModelData = controller.getModel().getActivePerWalletModelData();

        if (!perWalletModelData.isBusy()) {
            perWalletModelData.setBusy(true);
            perWalletModelData.setBusyTask(controller.getLocaliser().getString("importPrivateKeysSubmitAction.text"));
            perWalletModelData.setBusyTaskVerb(controller.getLocaliser().getString("importPrivateKeysSubmitAction.verb"));

            importPrivateKeysPanel.setMessageText1(controller.getLocaliser().getString(
                    "importPrivateKeysSubmitAction.importingPrivateKeys"));
            importPrivateKeysPanel.setMessageText2(" ");

            controller.fireWalletBusyChange(true);

            importPrivateKeysInBackground(privateKeyAndDateArray, walletPassword);
        }
    }
    
    /**
     * Import the private keys in a background Swing worker thread.
     */
    private void importPrivateKeysInBackground(final Collection<PrivateKeyAndDate> privateKeyAndDateArray,
            final CharSequence walletPassword) {
        final PerWalletModelData finalPerWalletModelData = controller.getModel().getActivePerWalletModelData();
        final ImportPrivateKeysPanel finalImportPanel = importPrivateKeysPanel;
        final MultiBitController finalController = controller;

        SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
            private String statusBarMessage = null;
            private String uiMessage = null;

            @Override
            protected Boolean doInBackground() throws Exception {
                Boolean successMeasure = Boolean.FALSE;
                boolean keyEncryptionRequired = false;
                try {
                    Wallet walletToAddKeysTo = finalPerWalletModelData.getWallet();

                    Collection<byte[]> unencryptedWalletPrivateKeys = new ArrayList<byte[]>();
                    Date earliestTransactionDate = new Date(DateUtils.nowUtc().getMillis());

                    if (walletToAddKeysTo.getEncryptionType() != EncryptionType.UNENCRYPTED) {
                        keyEncryptionRequired = true;
                    }

                    try {
                        if (walletToAddKeysTo != null) {
                            synchronized (walletToAddKeysTo.keychain) {
                                // Work out what the unencrypted private keys are.
                                KeyCrypter walletKeyCrypter = walletToAddKeysTo.getKeyCrypter();
                                KeyParameter aesKey = null;
                                if (keyEncryptionRequired) {
                                    if (walletKeyCrypter == null) {
                                        log.error("Missing KeyCrypter. Could not decrypt private keys.");
                                    }
                                    aesKey = walletKeyCrypter.deriveKey(CharBuffer.wrap(walletPassword));
                                }
                                for (ECKey ecKey : walletToAddKeysTo.keychain) {
                                    if (keyEncryptionRequired) {
                                        if (ecKey.getEncryptedPrivateKey() == null
                                                || ecKey.getEncryptedPrivateKey().getEncryptedBytes() == null
                                                || ecKey.getEncryptedPrivateKey().getEncryptedBytes().length == 0) {

                                            log.error("Missing encrypted private key bytes for key " + ecKey.toString()
                                                    + ", enc.priv = "
                                                    + Utils.bytesToHexString(ecKey.getEncryptedPrivateKey().getEncryptedBytes()));
                                        } else {
                                            byte[] decryptedPrivateKey = ecKey.getKeyCrypter().decrypt(
                                                    ecKey.getEncryptedPrivateKey(), aesKey);
                                            unencryptedWalletPrivateKeys.add(decryptedPrivateKey);
                                        }

                                    } else {
                                        // Wallet is not encrypted.
                                        unencryptedWalletPrivateKeys.add(ecKey.getPrivKeyBytes());
                                    }
                                }

                                // Keep track of earliest transaction date go backwards from now.
                                if (privateKeyAndDateArray != null) {
                                    for (PrivateKeyAndDate privateKeyAndDate : privateKeyAndDateArray) {
                                        ECKey keyToAdd = privateKeyAndDate.getKey();
                                        if (keyToAdd != null) {
                                            if (privateKeyAndDate.getDate() != null) {
                                                keyToAdd.setCreationTimeSeconds(privateKeyAndDate.getDate().getTime()
                                                        / NUMBER_OF_MILLISECONDS_IN_A_SECOND);
                                            }

                                            if (!keyChainContainsPrivateKey(unencryptedWalletPrivateKeys, keyToAdd, walletPassword)) {
                                                if (keyEncryptionRequired) {
                                                    ECKey encryptedKey = new ECKey(walletKeyCrypter.encrypt(
                                                            keyToAdd.getPrivKeyBytes(), aesKey), keyToAdd.getPubKey(),
                                                            walletKeyCrypter);
                                                    walletToAddKeysTo.addKey(encryptedKey);
                                                } else {
                                                    walletToAddKeysTo.addKey(keyToAdd);
                                                }

                                                // Update earliest transaction date.
                                                if (privateKeyAndDate.getDate() == null) {
                                                    // Need to go back to the genesis block.
                                                    earliestTransactionDate = null;
                                                } else {
                                                    if (earliestTransactionDate != null) {
                                                        earliestTransactionDate = earliestTransactionDate.before(privateKeyAndDate
                                                                .getDate()) ? earliestTransactionDate : privateKeyAndDate.getDate();
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        // Wipe the collection of private key bytes to remove it
                        // from memory.
                        for (byte[] privateKeyBytes : unencryptedWalletPrivateKeys) {
                            if (privateKeyBytes != null) {
                                for (int i = 0; i < privateKeyBytes.length; i++) {
                                    privateKeyBytes[i] = 0;
                                }
                            }
                        }
                    }

                    log.debug(walletToAddKeysTo.toString());

                    controller.getFileHandler().savePerWalletModelData(finalPerWalletModelData, false);

                    controller.getModel().createAddressBookReceivingAddresses(finalPerWalletModelData.getWalletFilename());

                    // Import was successful.
                    uiMessage = finalController.getLocaliser().getString("importPrivateKeysSubmitAction.privateKeysImportSuccess");

                    privateKeysBackupFile = controller.getFileHandler().backupPrivateKeys(CharBuffer.wrap(walletPassword));

                    // Begin blockchain replay - returns quickly - just kicks it off.
                    log.debug("Starting replay from date = " + earliestTransactionDate);
                    if (performReplay) {
                        List<PerWalletModelData> perWalletModelDataList = new ArrayList<PerWalletModelData>();
                        perWalletModelDataList.add(finalPerWalletModelData);
                        
                        // Initialise the message shown in the SingleWalletPanel
                        if (mainFrame != null) {
                            WalletListPanel walletListPanel = mainFrame.getWalletsView();
                            if (walletListPanel != null) {
                                SingleWalletPanel singleWalletPanel = walletListPanel.findWalletPanelByFilename(finalPerWalletModelData.getWalletFilename());

                                if (singleWalletPanel != null) {
                                    singleWalletPanel.setSyncMessage(controller.getLocaliser().getString("importPrivateKeysSubmitAction.verb"), Message.NOT_RELEVANT_PERCENTAGE_COMPLETE);
                                }
                            }
                        }
                        ReplayTask replayTask = new ReplayTask(perWalletModelDataList, earliestTransactionDate, null, -1);
                        ReplayManager.INSTANCE.offerReplayTask(replayTask);
                        successMeasure = Boolean.TRUE;
                    }
                } catch (WalletSaveException wse) {
                    logError(wse);
                } catch (KeyCrypterException kce) {
                    logError(kce);
                } catch (PrivateKeysHandlerException pkhe) {
                    logError(pkhe);
                }
                return successMeasure;
            }
            
            private void logError(Exception e) {
                log.error(e.getClass().getName() + " " + e.getMessage());
                e.printStackTrace();
                uiMessage = controller.getLocaliser().getString("importPrivateKeysSubmitAction.privateKeysImportFailure",
                        new Object[] { e.getMessage() });

            }

            @Override
            protected void done() {
                try {
                    Boolean wasSuccessful = get();

                    if (finalImportPanel != null && uiMessage != null) {
                        finalImportPanel.setMessageText1(uiMessage);
                    }
                    
                    if (privateKeysBackupFile != null) {
                        try {
                            finalImportPanel.setMessageText2(controller.getLocaliser().getString(
                                    "changePasswordPanel.keysBackupSuccess",
                                    new Object[] { privateKeysBackupFile.getCanonicalPath() }));
                        } catch (IOException e1) {
                            log.debug(e1.getClass().getCanonicalName() + " " + e1.getMessage());
                        }
                    }

                    if (wasSuccessful) {
                        log.debug(statusBarMessage);
                        finalImportPanel.clearPasswords();
                    } else {
                        MessageManager.INSTANCE.addMessage(new Message(statusBarMessage));
                        log.error(statusBarMessage);
                    }
                } catch (Exception e) {
                    // Not really used but caught so that SwingWorker shuts down cleanly.
                    log.error(e.getClass() + " " + e.getMessage());
                } 
//                finally {
//                    // Declare that wallet is no longer busy with the task.
//                    finalPerWalletModelData.setBusyTask(null);
//                    finalPerWalletModelData.setBusy(false);
//                    controller.fireWalletBusyChange(false);                   
//                }
            }
        };
        log.debug("Importing private keys in background SwingWorker thread");
        worker.execute();
    }

    /**
     * Determine whether the key is already in the wallet.
     * @throws EncrypterDecrypterException 
     */
    private boolean keyChainContainsPrivateKey(Collection<byte[]> unencryptedPrivateKeys, ECKey keyToAdd, CharSequence walletPassword) throws KeyCrypterException {
        if (unencryptedPrivateKeys == null || keyToAdd == null) {
            return false;
        } else {
            byte[] unencryptedKeyToAdd = new byte[0];
            if (keyToAdd.isEncrypted()) {
                unencryptedKeyToAdd = keyToAdd.getKeyCrypter().decrypt(keyToAdd.getEncryptedPrivateKey(), keyToAdd.getKeyCrypter().deriveKey(walletPassword));
            }
            for (byte[] loopEncryptedPrivateKey : unencryptedPrivateKeys) { 
                if (Arrays.equals(unencryptedKeyToAdd, loopEncryptedPrivateKey)) {
                    return true;
                }
            }
            return false;
        }
    }

    // Used in testing.
    public void setPerformReplay(boolean performReplay) {
        this.performReplay = performReplay;
    }

    @Override
    public void walletBusyChange(boolean newWalletIsBusy) {
        // Update the enable status of the action to match the wallet busy status.
        if (controller.getModel().getActivePerWalletModelData().isBusy()) {
            // Wallet is busy with another operation that may change the private keys - Action is disabled.
            putValue(SHORT_DESCRIPTION, controller.getLocaliser().getString("multiBitSubmitAction.walletIsBusy", new Object[]{controller.getModel().getActivePerWalletModelData().getBusyTask()}));
            setEnabled(false);           
        } else {
            // Enable unless wallet has been modified by another process.
            if (!controller.getModel().getActivePerWalletModelData().isFilesHaveBeenChangedByAnotherProcess()) {
                putValue(SHORT_DESCRIPTION, controller.getLocaliser().getString("importPrivateKeysSubmitAction.text"));
                setEnabled(true);
            }
        }
    }
}