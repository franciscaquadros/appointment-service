package pt.healthcare.appointments.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(requiredProperties = {"items", "nextCursor"})
public record AppointmentPage(
    List<AppointmentView> items,
    @Schema(
            description = "Opaque URL-safe cursor for the next page; null on the last page.",
            nullable = true)
        @Nullable String nextCursor) {}
