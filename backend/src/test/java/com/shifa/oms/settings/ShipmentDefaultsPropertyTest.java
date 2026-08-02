package com.shifa.oms.settings;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.settings.dto.SettingsRequest;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature: shopify-quikshipx-order-sync, Property 37: Shipment defaults are applied
 * and validated.
 *
 * <p>For any Shipment_Defaults values, each of package type, shipping mode, dead
 * weight, the three dimensions and shipping amount is accepted if and only if it lies
 * in its permitted range, and a rejected value leaves every stored default unchanged.
 *
 * <p>The stored-state guarantee is the part that matters operationally: a half-applied
 * update would send QuikShipX a body mixing old and new parcel dimensions.
 *
 * <p>Validates: Requirements 16.1, 16.4, 16.5
 *
 * <p>Java 25 note: Mockito cannot mock concrete classes on this runtime, so the
 * repository double is a reflective proxy over the two methods the service uses.
 */
class ShipmentDefaultsPropertyTest {

    // --- Accepted ranges ---------------------------------------------------

    @Property(tries = 500)
    void valuesInsideTheirRangesAreAppliedVerbatim(
            @ForAll @IntRange(min = 1, max = 50_000) int weight,
            @ForAll @IntRange(min = 1, max = 200) int length,
            @ForAll @IntRange(min = 1, max = 200) int width,
            @ForAll @IntRange(min = 1, max = 200) int height) {

        Fake fake = new Fake();
        SettingsService service = new SettingsService(fake.repository());

        AppSettings saved = service.update(request(b -> {
            b.warehouseId = "65";
            b.packageType = "2";
            b.shippingMode = "2";
            b.weight = weight;
            b.length = length;
            b.width = width;
            b.height = height;
            b.shippingAmount = new BigDecimal("60.00");
            b.defaultCategory = "Herbal";
        }));

        assertThat(saved.getShipPickupWarehouseId()).isEqualTo("65");
        assertThat(saved.getShipPackageType()).isEqualTo("2");
        assertThat(saved.getShipShippingMode()).isEqualTo("2");
        assertThat(saved.getShipDeadWeightGrams()).isEqualTo(weight);
        assertThat(saved.getShipLengthCm()).isEqualTo(length);
        assertThat(saved.getShipWidthCm()).isEqualTo(width);
        assertThat(saved.getShipHeightCm()).isEqualTo(height);
        assertThat(saved.getShipShippingAmount()).isEqualByComparingTo("60.00");
        assertThat(saved.getShipDefaultCategory()).isEqualTo("Herbal");
        // A configured warehouse is the one thing that unblocks publication.
        assertThat(saved.shipmentDefaultsComplete()).isTrue();
    }

    // --- Rejected ranges leave state untouched -----------------------------

    @Property(tries = 500)
    void anOutOfRangeWeightIsRejectedAndChangesNothing(
            @ForAll @IntRange(min = -5_000, max = 200_000) int weight) {

        boolean valid = weight >= 1 && weight <= 50_000;
        assertRangeBehaviour(valid, "shipDeadWeightGrams", b -> b.weight = weight,
                s -> s.getShipDeadWeightGrams(), weight);
    }

    @Property(tries = 500)
    void anOutOfRangeLengthIsRejectedAndChangesNothing(
            @ForAll @IntRange(min = -50, max = 1_000) int length) {

        boolean valid = length >= 1 && length <= 200;
        assertRangeBehaviour(valid, "shipLengthCm", b -> b.length = length,
                s -> s.getShipLengthCm(), length);
    }

    @Property(tries = 500)
    void anOutOfRangeWidthIsRejectedAndChangesNothing(
            @ForAll @IntRange(min = -50, max = 1_000) int width) {

        boolean valid = width >= 1 && width <= 200;
        assertRangeBehaviour(valid, "shipWidthCm", b -> b.width = width,
                s -> s.getShipWidthCm(), width);
    }

    @Property(tries = 500)
    void anOutOfRangeHeightIsRejectedAndChangesNothing(
            @ForAll @IntRange(min = -50, max = 1_000) int height) {

        boolean valid = height >= 1 && height <= 200;
        assertRangeBehaviour(valid, "shipHeightCm", b -> b.height = height,
                s -> s.getShipHeightCm(), height);
    }

    @Property(tries = 300)
    void onlyOneAndTwoAreAcceptedForPackageTypeAndShippingMode(
            @ForAll @IntRange(min = 0, max = 9) int digit) {

        String value = String.valueOf(digit);
        boolean valid = "1".equals(value) || "2".equals(value);

        Fake packageFake = new Fake();
        SettingsService packageService = new SettingsService(packageFake.repository());
        if (valid) {
            assertThat(packageService.update(request(b -> b.packageType = value)).getShipPackageType())
                    .isEqualTo(value);
        } else {
            assertThatThrownBy(() -> packageService.update(request(b -> b.packageType = value)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("shipPackageType");
        }

        Fake modeFake = new Fake();
        SettingsService modeService = new SettingsService(modeFake.repository());
        if (valid) {
            assertThat(modeService.update(request(b -> b.shippingMode = value)).getShipShippingMode())
                    .isEqualTo(value);
        } else {
            assertThatThrownBy(() -> modeService.update(request(b -> b.shippingMode = value)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("shipShippingMode");
        }
    }

    @Property(tries = 300)
    void shippingAmountIsBoundedAtZeroAndNinetyNineThousand(
            @ForAll @IntRange(min = -1_000, max = 200_000) int rupees) {

        BigDecimal amount = new BigDecimal(rupees).setScale(2);
        boolean valid = rupees >= 0 && amount.compareTo(new BigDecimal("99999.99")) <= 0;

        Fake fake = new Fake();
        SettingsService service = new SettingsService(fake.repository());

        if (valid) {
            assertThat(service.update(request(b -> b.shippingAmount = amount)).getShipShippingAmount())
                    .isEqualByComparingTo(amount);
        } else {
            assertThatThrownBy(() -> service.update(request(b -> b.shippingAmount = amount)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("shipShippingAmount");
        }
    }

    // --- Null means "leave unchanged" --------------------------------------

    @Property(tries = 200)
    void omittingEveryShipmentFieldPreservesTheStoredDefaults(
            @ForAll @IntRange(min = 1, max = 50_000) int weight) {

        Fake fake = new Fake();
        SettingsService service = new SettingsService(fake.repository());

        // Configure once...
        service.update(request(b -> {
            b.warehouseId = "77";
            b.weight = weight;
        }));
        // ...then submit an update that knows nothing about shipment defaults.
        AppSettings after = service.update(request(b -> { }));

        // An older client must not be able to wipe the configuration.
        assertThat(after.getShipPickupWarehouseId()).isEqualTo("77");
        assertThat(after.getShipDeadWeightGrams()).isEqualTo(weight);
    }

    @Property(tries = 1)
    void publicationIsBlockedUntilTheWarehouseIsSet() {
        Fake fake = new Fake();
        SettingsService service = new SettingsService(fake.repository());

        // Defaults ship with no warehouse: publication must be gated (Req 16.6).
        assertThat(service.getSettings().shipmentDefaultsComplete()).isFalse();
        assertThat(service.update(request(b -> b.warehouseId = "65")).shipmentDefaultsComplete()).isTrue();

        // A blank value is treated as "leave unchanged", not as "clear", so a client
        // submitting an empty box cannot silently un-gate or re-gate publication.
        AppSettings afterBlank = service.update(request(b -> b.warehouseId = "   "));
        assertThat(afterBlank.getShipPickupWarehouseId()).isEqualTo("65");
        assertThat(afterBlank.shipmentDefaultsComplete()).isTrue();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void assertRangeBehaviour(boolean valid, String field,
                                      java.util.function.Consumer<Ship> mutate,
                                      java.util.function.Function<AppSettings, Integer> read,
                                      int value) {
        Fake fake = new Fake();
        SettingsService service = new SettingsService(fake.repository());
        AppSettings before = service.getSettings();
        Integer original = read.apply(before);

        if (valid) {
            assertThat(read.apply(service.update(request(mutate)))).isEqualTo(value);
        } else {
            assertThatThrownBy(() -> service.update(request(mutate)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining(field);
            // The rejected update must not have partially applied.
            assertThat(read.apply(fake.stored)).isEqualTo(original);
        }
    }

    /** Mutable holder for the shipment-default fields under test. */
    private static final class Ship {
        String warehouseId;
        String packageType;
        String shippingMode;
        Integer weight;
        Integer length;
        Integer width;
        Integer height;
        BigDecimal shippingAmount;
        String defaultCategory;
    }

    private static SettingsRequest request(java.util.function.Consumer<Ship> customiser) {
        Ship s = new Ship();
        customiser.accept(s);
        return new SettingsRequest(
                false, null, "Shifa Herbal Remedies", null, null, null, null,
                new BigDecimal("5.00"), true, null, null, null, 5,
                null, null, null, null, null, null, null, null,
                s.warehouseId, s.packageType, s.shippingMode,
                s.weight, s.length, s.width, s.height, s.shippingAmount, s.defaultCategory,
                null);
    }

    /** In-memory single-row settings store. */
    private static final class Fake {

        private AppSettings stored = AppSettings.defaults();

        AppSettingsRepository repository() {
            return (AppSettingsRepository) Proxy.newProxyInstance(
                    AppSettingsRepository.class.getClassLoader(),
                    new Class<?>[]{AppSettingsRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findById" -> Optional.of(stored);
                        case "save", "saveAndFlush" -> {
                            AppSettings incoming = (AppSettings) args[0];
                            if (incoming == null) {
                                throw new DataIntegrityViolationException("null settings");
                            }
                            stored = incoming;
                            yield stored;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
