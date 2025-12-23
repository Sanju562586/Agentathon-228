// content.js - Guardian Agent UI (Battle-Tested Edition)

console.log("Guardian Agent: Battle-Tested OTP Engine Active");

// --- Assets ---
const ICONS = {
    SHIELD: `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
    LOADING: `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#eab308" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="spin"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>`,
    SUCCESS: `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>`,
    ERROR: `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`
};

// --- React-Compatible Setter ---
function setNativeValue(element, value) {
    const descriptor = Object.getOwnPropertyDescriptor(element, 'value');
    const valueSetter = descriptor ? descriptor.set : undefined;

    const prototype = Object.getPrototypeOf(element);
    const prototypeDescriptor = Object.getOwnPropertyDescriptor(prototype, 'value');
    const prototypeValueSetter = prototypeDescriptor ? prototypeDescriptor.set : undefined;

    if (valueSetter && valueSetter !== prototypeValueSetter) {
        prototypeValueSetter.call(element, value);
    } else if (prototypeValueSetter) {
        prototypeValueSetter.call(element, value);
    } else {
        element.value = value;
    }

    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
    // Some frameworks listen for keyup to move focus
    element.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: value }));
}

// --- Styles Management (Shadow DOM aware) ---
const cssContent = `
    @keyframes spin { 100% { transform: rotate(360deg); } }
    .guardian-icon-wrapper svg.spin { animation: spin 1s linear infinite; }
    .guardian-icon-wrapper {
        position: absolute; right: 8px; top: 50%; transform: translateY(-50%); cursor: pointer; z-index: 99999; display: flex; align-items: center; justify-content: center; padding: 4px; border-radius: 4px; transition: background-color 0.2s;
    }
    .guardian-icon-wrapper:hover { background-color: rgba(0,0,0,0.05); }
    /* Modal Styles */
    #guardian-modal { font-family: sans-serif; animation: fadeIn 0.2s; position: fixed; top: 20px; right: 20px; background: white; color: #333; padding: 0; border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.15); z-index: 2147483647; border: 1px solid #ddd; min-width: 250px; }
    @keyframes fadeIn { from { opacity: 0; transform: translateY(-5px); } to { opacity: 1; transform: translateY(0); } }
`;

function injectStyles(root) {
    // Avoid duplicate injections
    if (root.__guardianStylesInjected) return;
    root.__guardianStylesInjected = true;

    const style = document.createElement('style');
    style.textContent = cssContent;
    if (root === document) {
        document.head.appendChild(style);
    } else {
        root.appendChild(style);
    }
}

// --- Deep DOM Traversal ---
function getAllInputs(root = document) {
    let inputs = [];
    // Inject styles in every root we traverse
    injectStyles(root === document ? document : root);

    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT, null, false);
    while (walker.nextNode()) {
        const node = walker.currentNode;
        if (node.tagName === 'INPUT') {
            inputs.push(node);
        }
        if (node.shadowRoot) {
            inputs = inputs.concat(getAllInputs(node.shadowRoot));
        }
    }
    return inputs;
}

// --- Inject UI Helper ---
function attachShield(input, type, onClick) {
    // Check flags based on type
    if (type === 'password' && input.dataset.guardianPassword) return;
    if (type === 'otp' && input.dataset.guardianOtp) return;

    // Mark as injected
    if (type === 'password') input.dataset.guardianPassword = "true";
    if (type === 'otp') input.dataset.guardianOtp = "true";

    const wrapper = document.createElement("div");
    wrapper.className = "guardian-icon-wrapper";
    wrapper.innerHTML = ICONS.SHIELD;
    wrapper.title = type === 'otp' ? "Auto-Fill OTP" : "Guardian Identity";

    // Handle positioning
    const parent = input.parentElement;
    const style = window.getComputedStyle(parent);
    if (style.position === 'static') {
        parent.style.position = 'relative';
    }

    // Correctly append (Works in Shadow DOM too if we found the input there)
    parent.appendChild(wrapper);

    wrapper.onclick = async (e) => {
        e.preventDefault();
        e.stopPropagation();
        wrapper.innerHTML = ICONS.LOADING;
        try {
            await onClick(input, wrapper);
        } catch (err) {
            console.error(err);
            wrapper.innerHTML = ICONS.ERROR;
        }
    };
}

// --- OTP Detection Logic (Visual + Semantic) ---
function detectOtpFields(inputs) {
    const candidates = [];

    // 1. Semantic Check
    const semanticRegex = /otp|code|pin|verification|2fa|digit/i;

    inputs.forEach(input => {
        // Skip hidden/disabled
        if (input.type === 'hidden' || input.disabled || input.style.display === 'none') return;

        let score = 0;

        // High Confidence Attributes
        if (input.autocomplete === "one-time-code") score += 10;
        if (input.inputMode === "numeric") score += 3;
        if (input.maxLength === 1) score += 5; // Split field chunk

        // Naming
        const nameParams = [input.id, input.name, input.placeholder, input.getAttribute('aria-label')];
        if (nameParams.some(n => n && semanticRegex.test(n))) score += 5;

        // "Password" fields often used for OTPs in banking
        if (input.type === 'password' && input.maxLength > 0 && input.maxLength < 10) score += 2;

        // Pattern
        if (input.pattern === "[0-9]*" || input.pattern === "\\d*") score += 3;

        if (score >= 5) {
            candidates.push(input);
        }
    });

    // 2. Visual Grouping (The "Cluster" Logic)
    // We look for inputs that are physically close to each other
    const clusters = [];

    // Convert to rects
    const rects = candidates.map(input => ({
        input,
        rect: input.getBoundingClientRect()
    })).filter(item => item.rect.width > 0 && item.rect.height > 0); // Visible only

    // Sort by Y then X
    rects.sort((a, b) => {
        const dy = Math.abs(a.rect.top - b.rect.top);
        if (dy > 10) return a.rect.top - b.rect.top;
        return a.rect.left - b.rect.left;
    });

    let currentCluster = [];

    rects.forEach((item, index) => {
        if (currentCluster.length === 0) {
            currentCluster.push(item);
            return;
        }

        const last = currentCluster[currentCluster.length - 1];

        // Check Proximity
        const verticalAligned = Math.abs(item.rect.top - last.rect.top) < 20;
        const horizontalClose = (item.rect.left - last.rect.right) < 50; // Max 50px gap

        if (verticalAligned && horizontalClose) {
            currentCluster.push(item);
        } else {
            // End Cluster
            if (currentCluster.length >= 3 && currentCluster.length <= 8) {
                clusters.push(currentCluster);
            }
            currentCluster = [item];
        }
    });

    // Flush last
    if (currentCluster.length >= 3 && currentCluster.length <= 8) {
        clusters.push(currentCluster);
    }

    // Mark cluster members
    clusters.flat().forEach(item => {
        // We prioritize the FIRST input of a cluster for the shield usage usually, 
        // but here we mark all, and only inject shield on the first one or all?
        // Let's inject on ALL for split fields, it's friendlier.
        attachShield(item.input, 'otp', handleOtpClick);
        // Tag them as belonging to a cluster for filling logic
        item.input.dataset.guardianClusterId = clusters.indexOf(currentCluster) + "-" + Date.now();
    });

    // Also mark semantic single-fields (length > 2)
    inputs.forEach(input => {
        if (!input.dataset.guardianOtp &&
            (input.autocomplete === "one-time-code" ||
                (input.name && semanticRegex.test(input.name) && input.maxLength !== 1))) {
            attachShield(input, 'otp', handleOtpClick);
        }
    });
}

// --- Handlers ---
async function handleOtpClick(input, wrapper) {
    wrapper.innerHTML = ICONS.LOADING;

    chrome.runtime.sendMessage({ type: "START_LOGIN", service: "otp_request" }, (response) => {
        if (!response || !response.success) {
            wrapper.innerHTML = ICONS.ERROR;
            alert("No OTP found or Agent unreachable.");
            return;
        }

        try {
            const json = JSON.parse(response.credentials);
            const code = json.otp;
            if (!code) throw new Error("No OTP code");

            // Fill Logic
            // 1. Is this part of a visual cluster?
            // To be robust, we re-calculate visual siblings on click to ensure fresh DOM state
            let siblings = getVisualSiblings(input);

            if (siblings.length > 1) {
                // Split Fill
                const chars = code.split('');
                siblings.forEach((sib, idx) => {
                    if (idx < chars.length) {
                        setNativeValue(sib, chars[idx]);
                    }
                });
            } else {
                // Single Fill
                setNativeValue(input, code);
            }

            wrapper.innerHTML = ICONS.SUCCESS;
        } catch (e) {
            console.error(e);
            wrapper.innerHTML = ICONS.ERROR;
        }
    });
}

function getVisualSiblings(refInput) {
    // Quick re-scan of visible inputs nearby
    const all = getAllInputs(document); // Expensive but accurate
    const refRect = refInput.getBoundingClientRect();

    // Find inputs on same Y-plane (+/- 10px)
    return all.filter(inp => {
        if (inp.type === 'hidden') return false;
        const r = inp.getBoundingClientRect();
        return Math.abs(r.top - refRect.top) < 15 && Math.abs(r.height - refRect.height) < 10;
    }).sort((a, b) => a.getBoundingClientRect().left - b.getBoundingClientRect().left);
}


// --- Main Scan Loop ---
function scan() {
    const all = getAllInputs(document);

    // 1. Password Fields
    all.filter(i => i.type === 'password' && !i.dataset.guardianPassword).forEach(inp => {
        // Avoid marking OTP fields as password if they look small/numeric
        if (inp.maxLength > 0 && inp.maxLength < 6 && inp.inputMode === 'numeric') return;
        attachShield(inp, 'password', handlePasswordClick);
    });

    // 2. OTP Fields
    detectOtpFields(all);
}

// Debounce helper
let timeout;
const observer = new MutationObserver(() => {
    clearTimeout(timeout);
    timeout = setTimeout(scan, 500); // 500ms debounce
});

// Init
// Init
scan();
observer.observe(document.body, { childList: true, subtree: true });

// --- Credential Selection UI ---
function createSelectionModal(credentials, onSelect) {
    // Remove existing
    const existing = document.getElementById("guardian-modal");
    if (existing) existing.remove();

    const modal = document.createElement("div");
    modal.id = "guardian-modal";
    // Style handled by cssContent injected earlier

    // Header
    const header = document.createElement("div");
    Object.assign(header.style, {
        padding: "12px 16px",
        backgroundColor: "#f8fafc",
        borderBottom: "1px solid #e2e8f0",
        fontWeight: "600",
        fontSize: "14px",
        color: "#334155",
        display: "flex",
        justifyContent: "space-between",
        alignItems: "center"
    });
    header.innerText = "Select Account";

    const closeBtn = document.createElement("span");
    closeBtn.innerHTML = "&times;";
    Object.assign(closeBtn.style, { cursor: "pointer", fontSize: "18px", color: "#94a3b8" });
    closeBtn.onclick = () => modal.remove();
    header.appendChild(closeBtn);
    modal.appendChild(header);

    // List
    const list = document.createElement("div");
    list.style.padding = "8px";

    credentials.forEach(cred => {
        const item = document.createElement("div");
        Object.assign(item.style, {
            padding: "10px 12px",
            borderRadius: "6px",
            cursor: "pointer",
            display: "flex",
            flexDirection: "column",
            marginBottom: "4px",
            border: "1px solid transparent"
        });

        const user = document.createElement("span");
        user.innerText = cred.username;
        Object.assign(user.style, { fontWeight: "500", fontSize: "14px", color: "#0f172a" });

        const app = document.createElement("span");
        app.innerText = cred.appName || "Service";
        Object.assign(app.style, { fontSize: "12px", color: "#64748b", marginTop: "2px" });

        item.appendChild(user);
        item.appendChild(app);

        item.onmouseenter = () => { item.style.backgroundColor = "#f1f5f9"; item.style.borderColor = "#cbd5e1"; };
        item.onmouseleave = () => { item.style.backgroundColor = "transparent"; item.style.borderColor = "transparent"; };
        item.onclick = () => {
            onSelect(cred);
            modal.remove();
        };

        list.appendChild(item);
    });

    modal.appendChild(list);
    document.body.appendChild(modal);
}

// --- Filling Logic (Robust 3-Stage Search) ---
function fillCredentials(passwordInput, username, password) {
    // 1. Fill Password
    setNativeValue(passwordInput, password);

    // 2. Find Username/Email field
    let target = null;

    // Strategy A: Previous Sibling/Proximity (Most Accurate)
    // Works for standard vertical forms (Email \n Password)
    const siblings = getVisualSiblings(passwordInput); // Re-use our visual logic
    const visualPredecessors = siblings.filter(s =>
        s.getBoundingClientRect().top < passwordInput.getBoundingClientRect().top
    );
    if (visualPredecessors.length > 0) {
        // Pick the closest one above
        target = visualPredecessors[visualPredecessors.length - 1];
    }

    // Strategy B: Form Scope (If A failed)
    // Look for semantic username fields within the same <form>
    if (!target && passwordInput.form) {
        const form = passwordInput.form;
        const candidates = Array.from(form.querySelectorAll('input:not([type="hidden"]):not([type="password"]):not([type="submit"]):not([type="button"])'));

        // Priority 1: Semantic Attributes
        target = candidates.find(i =>
            i.autocomplete === "username" ||
            i.autocomplete === "email" ||
            i.type === "email" ||
            i.type === "tel"
        );

        // Priority 2: Name/ID heuristics
        if (!target) {
            target = candidates.find(i =>
                /user|login|email|phone|mobile|id/i.test(i.name || "") ||
                /user|login|email|phone|mobile|id/i.test(i.id || "")
            );
        }

        // Priority 3: Just the first visible text input (Desperation)
        if (!target && candidates.length > 0) {
            target = candidates[0];
        }
    }

    // Strategy C: Global Previous Input (If no form or A & B failed)
    if (!target) {
        const all = getAllInputs(document);
        const passIdx = all.indexOf(passwordInput);
        if (passIdx > 0) {
            // Scan backwards for 5 slots
            for (let i = passIdx - 1; i >= Math.max(0, passIdx - 5); i--) {
                const c = all[i];
                if (c.type !== 'hidden' && c.type !== 'password' && c.style.display !== 'none') {
                    target = c;
                    break;
                }
            }
        }
    }

    if (target) {
        console.log("Guardian: Identified Identity Field:", target);
        setNativeValue(target, username);
    } else {
        console.warn("Guardian: Could not locate Identity Field. Filled Password only.");
    }
}

// --- Identity/Password Click Handler ---
async function handlePasswordClick(input, wrapper) {
    wrapper.innerHTML = ICONS.LOADING;

    // Dynamic Service Identification
    let service = window.location.hostname;
    service = service.replace(/^www\./, ''); // Strip www.
    // We send the raw hostname (e.g. "netflix.com", "accounts.google.com")
    // The Android App's "findMatches" Logic (fuzzy search) handles the rest.

    console.log(`Requesting access for: ${service}`);

    chrome.runtime.sendMessage({ type: "START_LOGIN", service: service }, (response) => {
        if (chrome.runtime.lastError) {
            console.error("Connection Error:", chrome.runtime.lastError);
            wrapper.innerHTML = ICONS.ERROR;
            return;
        }

        if (response && response.success) {
            wrapper.innerHTML = ICONS.SUCCESS;

            let creds = [];
            try {
                const raw = response.credentials;
                if (!raw) throw new Error("Empty Response");

                if (raw.startsWith("{") || raw.startsWith("[")) {
                    const json = JSON.parse(raw);
                    if (Array.isArray(json)) creds = json;
                    else creds = [json];
                } else {
                    creds = [{ username: "Unknown", password: raw, appName: "Legacy" }];
                }
            } catch (e) {
                console.error("Parse Error", e);
                wrapper.innerHTML = ICONS.ERROR;
                return;
            }

            if (creds.length === 0) {
                alert("No credentials found for " + service);
                wrapper.innerHTML = ICONS.SHIELD;
                return;
            }

            if (creds.length === 1) {
                fillCredentials(input, creds[0].username, creds[0].password);
            } else {
                createSelectionModal(creds, (selected) => {
                    fillCredentials(input, selected.username, selected.password);
                });
            }

            setTimeout(() => wrapper.innerHTML = ICONS.SHIELD, 2000);

        } else {
            console.error("Request failed:", response ? response.error : "Unknown");
            wrapper.innerHTML = ICONS.ERROR;
        }
    });
}
