package pt.healthcare.appointments.booking.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Booking-owned read projection, without integration state. */
public record AppointmentDetails(
    UUID id,
    String patientName,
    String patientEmail,
    String specialty,
    String doctor,
    String room,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    String status) {}
