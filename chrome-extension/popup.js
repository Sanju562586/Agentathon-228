// popup.js

document.addEventListener('DOMContentLoaded', async () => {
    const qrArea = document.getElementById('qr-area');
    const idDisplay = document.getElementById('device-id-display');

    // 1. Check if already paired
    const storage = await chrome.storage.local.get("isPaired");
    if (storage.isPaired) {
        idDisplay.innerText = "✅ DEVICE PAIRED";
        idDisplay.style.color = "#064E3B";
        idDisplay.style.backgroundColor = "#D1FAE5";
        qrArea.innerHTML = `
            <div style="display: flex; flex-direction: column; align-items: center; justify-content: center; animation: fadeIn 0.5s ease-out;">
                <div style="
                    font-size: 48px; 
                    color: #10B981; 
                    background: #ECFDF5; 
                    border-radius: 50%; 
                    width: 80px; 
                    height: 80px; 
                    display: flex; 
                    align-items: center; 
                    justify-content: center;
                    border: 4px solid #D1FAE5;
                ">✔</div>
                <div style="margin-top: 12px; font-weight: 800; color: #065F46; font-size: 16px; font-family: sans-serif;">CONNECTED</div>
            </div>
        `;
        return; // Stop here, no need to show QR or poll
    }

    // 2. Not paired? Generate Identity & QR
    chrome.runtime.sendMessage({ type: "GET_IDENTITY" }, (response) => {
        if (response && response.publicKey) {
            const pubKey = response.publicKey;

            // Hash the key to get a short "Device ID"
            crypto.subtle.digest('SHA-256', new TextEncoder().encode(pubKey)).then(hash => {
                const hashArray = Array.from(new Uint8Array(hash));
                const hashHex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
                const deviceId = "BROWSER-" + hashHex.substring(0, 8).toUpperCase();

                idDisplay.innerText = deviceId;

                // WE MUST SEND THE PUBLIC KEY TO THE PHONE SO IT CAN VERIFY SIGNATURES
                // Format: GUARDIAN_BIND:<PublicKeyPEM>
                // This will be a large QR code, but ML Kit can handle it.
                const bindData = `GUARDIAN_BIND:${pubKey}`;
                const qrUrl = `https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=${encodeURIComponent(bindData)}`;
                qrArea.innerHTML = `<img src="${qrUrl}" alt="Device QR" style="border-radius:4px; width:150px; height:150px;">`;

                const statusLabel = document.createElement("div");
                statusLabel.innerText = "Waiting for Scan...";
                Object.assign(statusLabel.style, {
                    marginTop: "12px",
                    fontWeight: "500",
                    color: "#64748b",
                    fontSize: "13px"
                });
                idDisplay.parentNode.appendChild(statusLabel);

                // Auto-Poll for Pairing Status
                let polling = true;

                function checkStatus() {
                    if (!polling) return;

                    chrome.runtime.sendMessage({ type: "START_LOGIN", service: "pairing_check" }, (resp) => {
                        if (resp && resp.credentials) {
                            try {
                                const status = JSON.parse(resp.credentials).status;
                                if (status === "PAIRED") {
                                    polling = false;
                                    idDisplay.innerText = "✅ DEVICE PAIRED";
                                    idDisplay.style.color = "#064E3B";
                                    idDisplay.style.backgroundColor = "#D1FAE5";
                                    qrArea.innerHTML = `
                                        <div style="display: flex; flex-direction: column; align-items: center; justify-content: center; animation: fadeIn 0.5s ease-out;">
                                            <div style="
                                                font-size: 48px; 
                                                color: #10B981; 
                                                background: #ECFDF5; 
                                                border-radius: 50%; 
                                                width: 80px; 
                                                height: 80px; 
                                                display: flex; 
                                                align-items: center; 
                                                justify-content: center;
                                                border: 4px solid #D1FAE5;
                                            ">✔</div>
                                            <div style="margin-top: 12px; font-weight: 800; color: #065F46; font-size: 16px; font-family: sans-serif;">CONNECTED</div>
                                        </div>
                                    `;
                                    statusLabel.remove();
                                } else {
                                    // Not paired yet, keep polling
                                    setTimeout(checkStatus, 2500);
                                }
                            } catch (e) {
                                setTimeout(checkStatus, 2500);
                            }
                        } else {
                            setTimeout(checkStatus, 2500);
                        }
                    });
                }

                // Start polling immediately
                checkStatus();
            });

        } else {
            idDisplay.innerText = "Error";
            qrArea.innerHTML = "❌";
        }
    });

    // 3. STORAGE LISTENER (Real-time update)
    chrome.storage.onChanged.addListener((changes, namespace) => {
        if (namespace === 'local' && changes.isPaired && changes.isPaired.newValue === true) {
            idDisplay.innerText = "✅ DEVICE PAIRED";
            idDisplay.style.color = "#064E3B";
            idDisplay.style.backgroundColor = "#D1FAE5";
            qrArea.innerHTML = `
                <div style="display: flex; flex-direction: column; align-items: center; justify-content: center; animation: fadeIn 0.5s ease-out;">
                    <div style="
                        font-size: 48px; 
                        color: #10B981; 
                        background: #ECFDF5; 
                        border-radius: 50%; 
                        width: 80px; 
                        height: 80px; 
                        display: flex; 
                        align-items: center; 
                        justify-content: center;
                        border: 4px solid #D1FAE5;
                    ">✔</div>
                    <div style="margin-top: 12px; font-weight: 800; color: #065F46; font-size: 16px; font-family: sans-serif;">CONNECTED</div>
                </div>
            `;
        }
    });

});
