import numpy as np
import tensorflow as tf
import pandas as pd
from sklearn.model_selection import train_test_split

# 1. Define Synthetic Data Generator
def generate_synthetic_data(samples=5000):
    """
    Generates synthetic behavior data.
    Features: [screen_time, loc_delta, type_spd, type_var, active_hour, interval, motion, bt_peer]
    Label: 0 (Risk/Block), 1 (Trust/Allow)
    """
    np.random.seed(42)
    
    # SAFE Patterns (Trust = 1)
    # Location near 0 (Home), Active Hour near 0 (Normal), Motion 0/0.5 (Still/Walk), BT 1 (Peers)
    safe_samples = samples // 2
    safe_data = np.zeros((safe_samples, 8))
    safe_data[:, 0] = np.random.normal(0.8, 0.1, safe_samples) # Screen Time: High usage
    safe_data[:, 1] = np.random.exponential(0.1, safe_samples) # Loc: Mostly 0 (Home)
    safe_data[:, 2] = np.random.normal(0.5, 0.1, safe_samples) # Typing Speed: Avg
    safe_data[:, 3] = np.random.normal(0.2, 0.1, safe_samples) # Typing Var: Normal human
    safe_data[:, 4] = np.random.choice([0.0, 0.0, 0.1], safe_samples) # Active Hour: Normal
    safe_data[:, 5] = np.random.exponential(0.2, safe_samples) # Interval: Short gaps
    safe_data[:, 6] = np.random.choice([0.0, 0.5], safe_samples, p=[0.8, 0.2]) # Motion: Static/Walk
    safe_data[:, 7] = np.random.choice([1.0, 0.0], safe_samples, p=[0.9, 0.1]) # BT: Peers present
    safe_labels = np.ones(safe_samples)

    # RISKY Patterns (Trust = 0)
    # Location 1 (Away), Active Hour 1 (3AM), Motion 1 (Driving), BT 0 (Alone)
    risky_samples = samples // 2
    risky_data = np.zeros((risky_samples, 8))
    risky_data[:, 0] = np.random.normal(0.2, 0.2, risky_samples) # Screen: Low usage
    risky_data[:, 1] = np.random.normal(0.9, 0.1, risky_samples) # Loc: Far Away
    risky_data[:, 2] = np.random.normal(0.8, 0.2, risky_samples) # Typing: Fast/Bot
    risky_data[:, 3] = np.random.exponential(0.8, risky_samples) # Var: Chaotic
    risky_data[:, 4] = np.random.choice([1.0, 0.0], risky_samples) # Time: Weird
    risky_data[:, 5] = np.random.normal(0.8, 0.2, risky_samples) # Interval: Long gaps
    risky_data[:, 6] = np.random.choice([1.0, 0.5], risky_samples) # Motion: High Velocity
    risky_data[:, 7] = np.random.choice([0.0, 1.0], risky_samples, p=[0.9, 0.1]) # BT: Alone
    risky_labels = np.zeros(risky_samples)

    # Combine
    X = np.vstack([safe_data, risky_data])
    X = np.clip(X, 0.0, 1.0) # Ensure normalization
    y = np.concatenate([safe_labels, risky_labels])
    
    return X, y

# 2. Main Execution
if __name__ == "__main__":
    print("🧠 Generating Synthetic Data...")
    X, y = generate_synthetic_data()
                                
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2)
    
    print(f"   Training Shapes: {X_train.shape}")

    # 3. Create Model (Simple MLP)
    print("🏗️ Building TensorFlow Model...")
    model = tf.keras.Sequential([
        tf.keras.layers.Dense(16, activation='relu', input_shape=(8,)),
        tf.keras.layers.Dense(8, activation='relu'),
        tf.keras.layers.Dense(1, activation='sigmoid') # Output 0..1 (Trust Probability)
    ])
    
    model.compile(optimizer='adam', loss='binary_crossentropy', metrics=['accuracy'])
    
    # 4. Train
    print("🏋️ Training...")
    model.fit(X_train, y_train, epochs=10, batch_size=32, validation_data=(X_test, y_test))
    
    # 5. Convert to TFLite
    print("📦 Converting to TFLite...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    
    # 6. Save
    output_path = "risk_model.tflite"
    with open(output_path, "wb") as f:
        f.write(tflite_model)
        
    print(f"✅ Model saved to {output_path}")
    print("   -> Move this file to 'android-agent/app/src/main/assets/'")
