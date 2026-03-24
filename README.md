# Krida AI – Athlete Performance & Fitness Analysis System

---

## 1. Overview

Krida AI is a native Android application built using Kotlin that performs real-time athlete performance analysis using on-device computer vision and sensor fusion.

The system leverages MediaPipe Pose Landmarker for skeletal tracking and integrates inertial sensor data to evaluate athletic performance across multiple dimensions. The application is designed for high-density, real-world environments where external hardware is unavailable and network conditions may be unreliable.

---

## 2. System Architecture

| Layer                  | Description                                                                 |
|-----------------------|-----------------------------------------------------------------------------|
| Input Layer           | Camera frames + device sensors (accelerometer, gyroscope)                  |
| Perception Layer      | MediaPipe pose landmark detection (33 keypoints)                           |
| Processing Layer      | Joint angle computation, motion analysis, Kalman filtering                 |
| Fusion Layer          | Sensor fusion (vision + IMU data)                                          |
| Analytics Layer       | Performance metric evaluation (8 categories)                               |
| Output Layer          | Real-time feedback, rep counting, posture analysis                         |

---

## 3. Core Capabilities

### 3.1 Real-Time Pose Estimation
- Detects 33 body landmarks using MediaPipe Pose Landmarker  
- Processes continuous camera frames with optimized on-device inference  
- Maintains low-latency performance suitable for live feedback systems  

### 3.2 Joint Angle Computation

Joint angles are computed dynamically using landmark coordinates:

| Joint        | Landmarks Used                     | Purpose                              |
|--------------|----------------------------------|--------------------------------------|
| Elbow        | Shoulder–Elbow–Wrist             | Arm movement tracking                |
| Knee         | Hip–Knee–Ankle                   | Squat depth and leg extension        |
| Hip          | Shoulder–Hip–Knee                | Body posture and bending analysis    |
| Shoulder     | Left–Right Shoulder alignment    | Symmetry and posture correction      |
| Spine        | Head–Shoulder–Hip                | Overall posture and stability        |

Applications include:
- Squat depth validation  
- Push-up form correction  
- Posture alignment analysis  
- Range-of-motion tracking  

---

## 4. Performance Evaluation Model

The system evaluates athletes across the following categories:

| Category                     | Methodology                                                                 |
|----------------------------|------------------------------------------------------------------------------|
| Speed & Acceleration       | Displacement over time + accelerometer fusion                               |
| Strength & Endurance       | Repetition counting + fatigue detection                                     |
| Explosiveness              | Velocity spikes and rapid force generation                                  |
| Stamina                    | Sustained activity tracking and efficiency degradation                      |
| Agility & Quickness        | Directional changes and transition speed                                    |
| Mobility & Flexibility     | Joint range of motion analysis                                              |
| Balance & Stability        | Center-of-mass estimation and sway detection                                |
| Reaction Time & Coordination | Response latency and limb synchronization                                  |

---

## 5. Sensor Fusion & Motion Intelligence

### 5.1 Sensors Utilized

| Sensor         | Role                                      |
|----------------|-------------------------------------------|
| Accelerometer  | Linear motion and displacement tracking   |
| Gyroscope      | Orientation and angular velocity          |

### 5.2 Kalman Filter Integration

- Combines pose estimation data with IMU sensor readings  
- Reduces noise and improves motion accuracy  
- Enables reliable detection of:
  - Sprint acceleration  
  - Explosive movements  
  - Rapid directional transitions  

---

## 6. Real-Time Processing Pipeline

1. Camera frame acquisition  
2. Pose landmark extraction (MediaPipe)  
3. Joint angle computation  
4. Sensor data acquisition (IMU)  
5. Sensor fusion via Kalman filtering  
6. Performance metric computation  
7. Real-time feedback rendering  

---

## 7. Supported Activities

- Push-ups (form analysis + repetition counting)  
- Squats (depth + posture tracking)  
- Sprint and acceleration analysis  
- General athletic movement evaluation  

---

## 8. Backend & Tamper-Proof Data Handling

To ensure integrity and prevent manipulation of recorded performance data, the system incorporates a structured processing approach:

### 8.1 Chunk-Based Data Processing (FFmpeg)

- Recorded sessions are segmented into **small video/data chunks** using FFmpeg  
- Each chunk is processed independently to avoid full-file manipulation  
- Enables efficient streaming, storage, and verification  

### 8.2 Tamper Resistance Strategy

| Mechanism                     | Description                                                                 |
|------------------------------|-----------------------------------------------------------------------------|
| Chunk Segmentation           | Prevents full-sequence editing without detection                           |
| Sequential Processing        | Maintains ordered data integrity                                           |
| Frame-Level Consistency      | Detects anomalies in motion continuity                                     |
| Reprocessing Validation      | Allows recomputation of metrics from raw chunks                            |

### 8.3 Benefits

- Reduces risk of performance spoofing  
- Ensures reliability of athlete metrics  
- Enables scalable backend processing pipelines  

---

## 9. Technology Stack

| Category        | Technology Used                          |
|-----------------|------------------------------------------|
| Language        | Kotlin                                   |
| Platform        | Native Android                           |
| AI/ML           | MediaPipe Pose Landmarker                |
| Sensors         | Accelerometer, Gyroscope                 |
| Processing      | On-device inference, Kalman Filtering    |
| Media Handling  | FFmpeg (chunk-based processing)          |

---

## 10. Requirements

- Android device (SDK 24+)  
- Camera access required  
- Recommended: Physical device for real-time performance  

---

## 11. Summary

Krida AI transforms a smartphone into a portable athlete performance analysis system by combining computer vision, sensor fusion, and real-time analytics. The system is designed to operate efficiently on-device while maintaining data integrity and reliability for performance evaluation.
