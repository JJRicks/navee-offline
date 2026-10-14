package local.navee.offlinelock;

/** Compact My Scooter captions. Telemetry names and stored values remain unchanged. */
final class ScooterReadingLabels {
    private ScooterReadingLabels() {}

    static String label(String key, String fallback) {
        if (key == null) return fallback;
        switch (key) {
            case "battery_percent": return "Battery charge (%)";
            case "voltage_v": return "Battery voltage";
            case "current_a": return "Battery current";
            case "power_w": return "Battery power";
            case "temperature_c": return "Battery temp.";
            case "battery_health_percent": return "Reported health";
            case "battery_status": return "Battery status";
            case "charging_state": return "Charging (raw)";
            case "charge_cycles": return "Reported charges";
            case "energy_throughput_wh": return "Energy (unverified)";
            case "capacity_throughput_ah": return "Lifetime throughput";
            case "deep_discharge": return "Deep discharges";
            case "self_discharge_raw": return "Self-discharge (raw)";
            case "rated_capacity_ah": return "Rated capacity";
            case "health_adjusted_capacity_ah": return "Est. capacity (health)";
            case "battery_manufactured": return "Battery made";
            case "high_temperature_duration": return "High-temp exposure";
            case "extreme_temperature_charge_raw": return "Hot/cold charge time (raw)";
            case "temperature_sign_state": return "Temp. sign (raw)";
            case "current_sign_state": return "Current sign (raw)";

            case "speed_kmh": return "Speed";
            case "remaining_range_km": return "Est. range";
            case "trip_km": return "Trip distance";
            case "trip_minutes": return "Trip time";
            case "maximum_speed_kmh": return "Trip top speed";
            case "average_speed_kmh": return "Trip avg. speed";
            case "odometer_km": return "Odometer";
            case "driving_status": return "Ride status (raw)";
            case "stored_trips": return "Stored trips";

            case "binding_status": return "Account binding (raw)";
            case "driving_mode": return "Drive mode";
            case "lock_status": return "Electronic lock";
            case "cruise_control": return "Cruise (raw)";
            case "tail_light": return "Tail light (raw)";
            case "energy_recovery": return "Recovery (raw)";
            case "range_algorithm": return "Range algorithm (raw)";
            case "display_units": return "Display units";
            case "auto_sensor": return "Auto light (raw)";
            case "tyre_reminder": return "Tyre reminder (raw)";
            case "ambient_light": return "Line pattern (raw)";
            case "traction_control": return "Traction (raw)";
            case "turn_sound": return "Turn sound (raw)";
            case "proximity_key": return "Proximity key (raw)";
            case "night_mode": return "Night mode (raw)";
            case "range_algorithm_mode": return "Range mode (raw)";
            case "eco_light": return "Line colors (packed)";
            case "drive_light": return "Star pattern (raw)";
            case "sport_light": return "Star colors (packed)";
            case "start_speed": return "Start speed (raw)";
            case "speed_limit": return "Speed limit (raw)";
            case "volume": return "Audio (raw)";
            case "voice_language": return "Voice language (raw)";
            case "logo_light": return "Logo light (raw)";
            case "daytime_light": return "Daytime light (raw)";
            case "maximum_speed_setting": return "Max. speed setting (raw)";
            case "drive_mode_setting": return "Mode setting (raw)";
            case "scheduled_charge_time_raw": return "Charge schedule (raw)";
            case "charge_limit_percent": return "Charge limit";
            case "low_power": return "Low-battery mode (raw)";
            case "timed_charging": return "Timed charging (raw)";
            case "auto_lock_time": return "Lock duration (raw)";
            case "speed_limit_eligibility": return "Speed eligibility (raw)";
            case "weather": return "Weather (raw)";
            case "slope_assist": return "Hill assist (raw)";
            case "long_range_mode": return "Long range (raw)";
            case "overspeed_control": return "Overspeed control (raw)";
            case "overspeed_alert": return "Overspeed alert (raw)";
            case "drive_speed_limit": return "Drive speed limit (raw)";

            case "meter_version": return "Meter firmware";
            case "controller_version": return "Controller firmware";
            case "battery_version": return "Battery firmware";
            case "screen_version": return "Screen firmware";
            case "uwb_version": return "Proximity firmware";
            case "scooter_serial": return "Scooter serial";
            case "battery_serial_raw": return "Battery serial (raw)";

            case "warning_code": return "Warning code";
            case "lock_push_warning": return "Lock movement (raw)";
            case "hide_e9": return "E9 flag (raw)";
            case "ride_report_format": return "Ride format";
            case "ride_read_raw": return "Ride read (raw)";
            case "fault_history_raw": return "Fault history (raw)";
            case "response_70": return "Settings read · 70";
            case "response_71": return "Ride read · 71";
            case "response_72": return "Battery read · 72";
            case "response_73": return "Firmware read · 73";
            case "response_74": return "Scooter serial · 74";
            case "response_75": return "Battery serial · 75";
            case "response_76": return "Ride history · 76";
            case "response_79": return "Battery signs · 79";
            case "response_7b": return "Fault history · 7B";
            case "response_90": return "Home report · 90";
            case "response_91": return "Ride report v0 · 91";
            case "response_92": return "Ride report v1 · 92";
            default: return fallback;
        }
    }
}
