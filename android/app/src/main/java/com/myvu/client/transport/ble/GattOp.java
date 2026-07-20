package com.myvu.client.transport.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.os.Build;

import java.util.UUID;

/**
 * One queued GATT operation. Android allows exactly one outstanding operation
 * per connection, so every one of these is dispatched by {@link GattQueue} and
 * completed by the matching BluetoothGattCallback.
 */
public abstract class GattOp {

    /** Executes the operation; returns false if the stack rejected it outright. */
    public abstract boolean execute(BluetoothGatt gatt);

    public abstract String describe();

    @Override
    public String toString() {
        return describe();
    }

    // ------------------------------------------------------------- ops

    public static GattOp discoverServices() {
        return new GattOp() {
            @Override
            public boolean execute(BluetoothGatt gatt) {
                return gatt.discoverServices();
            }

            @Override
            public String describe() {
                return "discoverServices";
            }
        };
    }

    public static GattOp requestMtu(final int mtu) {
        return new GattOp() {
            @Override
            public boolean execute(BluetoothGatt gatt) {
                return gatt.requestMtu(mtu);
            }

            @Override
            public String describe() {
                return "requestMtu(" + mtu + ")";
            }
        };
    }

    /**
     * Enables notifications. This is TWO steps: the local-only
     * setCharacteristicNotification, plus an actual CCCD descriptor write to the
     * peer. bleak's start_notify did both invisibly; omitting the descriptor
     * write yields a connection that looks perfect and never receives a byte.
     */
    public static GattOp enableNotifications(final BluetoothGattCharacteristic ch) {
        return new GattOp() {
            @Override
            public boolean execute(BluetoothGatt gatt) {
                if (!gatt.setCharacteristicNotification(ch, true)) return false;

                BluetoothGattDescriptor cccd = ch.getDescriptor(Uuids.CCCD);
                if (cccd == null) return false;

                boolean canNotify =
                        (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                byte[] value = canNotify
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return gatt.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS;
                }
                cccd.setValue(value);
                return gatt.writeDescriptor(cccd);
            }

            @Override
            public String describe() {
                return "enableNotifications(" + shortUuid(ch.getUuid()) + ")";
            }
        };
    }

    /**
     * Writes without response, matching bleak's response=False. The queue still
     * waits for onCharacteristicWrite before dispatching the next op -- that is
     * what preserves fragment order (FAST_CTR then seq 1..N).
     */
    public static GattOp write(final BluetoothGattCharacteristic ch, final byte[] value) {
        return new GattOp() {
            @Override
            public boolean execute(BluetoothGatt gatt) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return gatt.writeCharacteristic(ch, value,
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                            == BluetoothGatt.GATT_SUCCESS;
                }
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                ch.setValue(value);
                return gatt.writeCharacteristic(ch);
            }

            @Override
            public String describe() {
                return "write(" + shortUuid(ch.getUuid()) + ", " + value.length + "B)";
            }
        };
    }

    static String shortUuid(UUID uuid) {
        String s = uuid.toString();
        return "0x" + s.substring(4, 8);
    }
}
