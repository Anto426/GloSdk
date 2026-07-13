# Stato del protocollo

## Supporto stabile Boreas

| Funzione | Characteristic | Payload | Conferma |
|---|---|---|---|
| Device info | `010a` | read | valore da 28 byte |
| Batteria | `030a` | read | `level, chargeCode, sessions, authenticity` |
| Lock | `040a` | `00` / `01` | write ack + notification |
| Session status | `070a` | read | valore da 9 byte |
| Auto-start | `070a` | `02 <bool>` | write ack + notification |
| Auto-stop | `070a` | `03 <bool>` | write ack + notification |
| Find ON | `090a` | `01 <seconds>` | write ack + notification |
| Find OFF | `090a` | `00 00` | write ack + notification |
| Luminosità | `0a0a` | `00..64` | write ack |
| Profilo normale/boost | `0c0a` | `00` / `01` | write ack + notification |
| Ora | `020a` | epoch seconds `uint32` big-endian | read/write ack |
| Lettura testi display | `010a` | `00 03` | notification protobuf |

Il comando EOS warning `01 <bool>` è incluso nell'API ma ha livello
`STATICALLY_VERIFIED`: non è stato azionato nella cattura fisica.

## UUID

Formato proprietario:

```text
6cd6c8b5-e378-[MODELLO]-[TIPO]-1b9740683449
```

Codici modello: Boreas `0204`, Adonis `0205`–`020a`.

Servizi: Session `000a`, Debug `000b`, Device Management `000c`, Age `000d`.

Characteristic Session: DeviceInfo `010a`, Time `020a`, Battery `030a`, Lock `040a`,
SessionRecords `060a`, SessionStatus `070a`, FindGlo `090a`, LED `0a0a`, Reset `0b0a`,
HeatingProfile `0c0a`, Haptic `0d0a`, Buzzer `0e0a`.

Debug: LastError `010b`, Logs `020b`, SessionLog `030b`.

Payload: Version `010c`, Control `020c`, Data `030c`, Challenge `040c`.

Age: Challenge `010d`, Signature `020d`.

OTA:

- Service `ae5d1e47-5c13-43a0-8635-82ad38a1381f`
- Control Point `a3dd50bf-f7a7-4e99-838e-570a086c661b`
- Data `a2e86c7a-d961-4091-b74f-2409e72efe26`
- CCCD `00002902-0000-1000-8000-00805f9b34fb`

## Ancora sperimentale

- pairing SMP iniziale dopo rimozione del bond;
- Haptic e Buzzer sul dispositivo fisico;
- SessionRecords e semantica completa degli event log;
- scrittura dei tre messaggi display tramite Payload challenge/firma;
- age verification;
- modalità bootloader e OTA/FOTA;
- servizio `00010203-0405-0607-0809-0a0b0c0d1910`.

Queste funzioni non devono essere presentate dall'app come operative finché non vengono
verificate con catture controllate.
