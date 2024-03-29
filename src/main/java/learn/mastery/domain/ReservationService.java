package learn.mastery.domain;

import learn.mastery.data.DataException;
import learn.mastery.data.GuestRepository;
import learn.mastery.data.ReservationRepository;
import learn.mastery.data.HostRepository;
import learn.mastery.models.Host;
import learn.mastery.models.Reservation;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final HostRepository hostRepository;
    private  final GuestRepository guestRepository;

    public ReservationService(ReservationRepository reservationRepository, HostRepository hostRepository, GuestRepository guestRepository) {
        this.reservationRepository = reservationRepository;
        this.hostRepository = hostRepository;
        this.guestRepository = guestRepository;
    }

    public List<Reservation> findByHostId(String hostId) throws IOException {
        return reservationRepository.findByHostId(hostId);
    }

    public Result<Reservation> makeReservation(Reservation reservation) throws IOException {
        Result<Reservation> result = validateReservation(reservation, false);

        if (!result.isSuccess()) {
            return result;
        }

        BigDecimal total = calculateTotal(reservation.getStartDate(), reservation.getEndDate(), reservation.getHost());
        reservation.setTotal(total);

        try {
            reservationRepository.add(reservation);
            result.setPayload(reservation);
        } catch (Exception | DataException e) {
            result.addErrorMessage("Failed to save the reservation: " + e.getMessage());
        }

        return result;
    }

    public Result<Reservation> updateReservation(Reservation updatedReservation) throws DataException, IOException {
        Result<Reservation> result = validateReservation(updatedReservation, false);

        if (!result.isSuccess()) {
            return result;
        }


        List<Reservation> existingReservations = reservationRepository.findByHostId(updatedReservation.getHost().getId());
        boolean exists = existingReservations.stream()
                .anyMatch(r -> r.getId() == updatedReservation.getId());

        if (!exists) {
            result.addErrorMessage("Reservation does not exist.");
            return result;
        }

        boolean conflict = existingReservations.stream()
                .filter(r -> r.getId() != updatedReservation.getId())
                .anyMatch(existing -> !isDateRangeValid(updatedReservation, existing));

        if (conflict) {
            result.addErrorMessage("Updated reservation conflicts with an existing reservation.");
            return result;
        }

        boolean success = reservationRepository.update(updatedReservation);
        if (!success) {
            result.addErrorMessage("Failed to update the reservation.");
            return result;
        }

        result.setPayload(updatedReservation);
        return result;
    }

    public Result<Boolean> deleteReservation(int reservationId, String hostId) {
        Result<Boolean> result = new Result<>();

        try {
            List<Reservation> existingReservations = reservationRepository.findByHostId(hostId);

            Reservation reservationToDelete = existingReservations.stream()
                    .filter(r -> r.getId() == reservationId)
                    .findFirst()
                    .orElse(null);

            if (reservationToDelete == null) {
                result.addErrorMessage("Reservation with ID " + reservationId + " does not exist.");
                result.setPayload(false);
                return result;
            }

            if (!reservationToDelete.getStartDate().isAfter(LocalDate.now())) {
                result.addErrorMessage("Cannot cancel a reservation that's in the past.");
                result.setPayload(false);
                return result;
            }

            boolean deleted = reservationRepository.delete(reservationToDelete);
            if (!deleted) {
                result.addErrorMessage("Failed to delete the reservation.");
                result.setPayload(false);
                return result;
            }

            result.setPayload(true);
        } catch (Exception | DataException e) {
            result.addErrorMessage("Failed to delete the reservation: " + e.getMessage());
            result.setPayload(false);
        }

        return result;
    }

    // VALIDATIONS VALIDATIONS VALIDATIONS VALIDATIONS VALIDATIONS VALIDATIONS VALIDATIONS VALIDATIONS

    private Result<Reservation> validateReservation(Reservation reservation, boolean isUpdate) throws IOException {
        Result<Reservation> result = new Result<>();

        if (reservation.getGuest() == null) {
            result.addErrorMessage("Guest is required.");
        }

        if (reservation.getHost() == null) {
            result.addErrorMessage("Host is required.");
        }

        if (reservation.getStartDate() == null || reservation.getEndDate() == null) {
            result.addErrorMessage("Start and end dates are required.");
        } else if (!reservation.getStartDate().isBefore(reservation.getEndDate())) {
            result.addErrorMessage("Start date must come before end date.");
        } else if (!isUpdate && !isReservationDateValid(reservation)) {
        }

        if (reservation.getStartDate() != null && !reservation.getStartDate().isAfter(LocalDate.now())) {
            result.addErrorMessage("Start date must be in the future.");
        }

        if (reservation.getGuest() != null && guestRepository.findByEmail(reservation.getGuest().getEmail()) == null) {
            result.addErrorMessage("Guest does not exist.");
        }

        if (reservation.getHost() != null && hostRepository.findByEmail(reservation.getHost().getEmail()) == null) {
            result.addErrorMessage("Host does not exist.");
        }

        if (!isReservationDateValid(reservation)) {
            result.addErrorMessage("Reservation dates overlap with an existing reservation.");
        }

        if (result.isSuccess()) {
            result.setPayload(reservation);
        }

        return result;
    }



    // HELPERS HELPERS HELPERS HELPERS HELPERS HELPERS HELPERS HELPERS HELPERS HELPERS HELPERS
    public BigDecimal calculateTotal(LocalDate startDate, LocalDate endDate, Host host) {
        BigDecimal total = BigDecimal.ZERO;
        LocalDate date = startDate;

        while (!date.isAfter(endDate)) {
            if (isWeekend(date)) {
                total = total.add(host.getWeekendRate());
            } else {
                total = total.add(host.getStandardRate());
            }
            date = date.plusDays(1);
        }
        return total;
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private boolean isReservationDateValid(Reservation newReservation) {
        List<Reservation> existingReservations = reservationRepository.findByHostId(newReservation.getHost().getId());

        for (Reservation existingReservation : existingReservations) {
            boolean isStartBeforeOrOnExistingEnd = newReservation.getStartDate().isBefore(existingReservation.getEndDate())
                    || newReservation.getStartDate().isEqual(existingReservation.getEndDate());

            boolean isEndAfterOrOnExistingStart = newReservation.getEndDate().isAfter(existingReservation.getStartDate())
                    || newReservation.getEndDate().isEqual(existingReservation.getStartDate());

            if (isStartBeforeOrOnExistingEnd && isEndAfterOrOnExistingStart) {
                // this means new reservation overlaps with an existing reservation
                return false;
            }
        }

        return true;
    }


    private boolean isReservationDateValidForUpdate(Reservation newReservation, Integer updatingReservationId) {
        List<Reservation> existingReservations = reservationRepository.findByHostId(newReservation.getHost().getId());

        for (Reservation existingReservation : existingReservations) {
            // skip the reservation being updated
            if (existingReservation.getId() == updatingReservationId) {
                continue;
            }

            boolean isStartBeforeOrOnExistingEnd = newReservation.getStartDate().isBefore(existingReservation.getEndDate())
                    || newReservation.getStartDate().isEqual(existingReservation.getEndDate());

            boolean isEndAfterOrOnExistingStart = newReservation.getEndDate().isAfter(existingReservation.getStartDate())
                    || newReservation.getEndDate().isEqual(existingReservation.getStartDate());

            if (isStartBeforeOrOnExistingEnd && isEndAfterOrOnExistingStart) {
                // this means new/updated reservation overlaps with another existing reservation
                return false;
            }
        }

        return true;
    }

    private boolean isDateRangeValid(Reservation updatedReservation, Reservation existingReservation) {
        LocalDate updatedStart = updatedReservation.getStartDate();
        LocalDate updatedEnd = updatedReservation.getEndDate();
        LocalDate existingStart = existingReservation.getStartDate();
        LocalDate existingEnd = existingReservation.getEndDate();

        return updatedStart.isAfter(existingEnd) || updatedEnd.isBefore(existingStart);
    }

}
