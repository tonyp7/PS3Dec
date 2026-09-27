from tests import synth


def test_length_matches_last_end_sector_plus_one():
    for regions in [(2, 2, 2), (4, 6, 4), (3, 5, 7, 2, 9)]:
        data = synth.build_decrypted(regions)
        n = int.from_bytes(data[:4], "big")
        assert n == (len(regions) + 1) // 2
        table = [int.from_bytes(data[12 + 4 * i : 16 + 4 * i], "big") for i in range(2 * n - 1)]
        assert len(data) == (table[-1] + 1) * synth.SECTOR == sum(regions) * synth.SECTOR
        assert table == sorted(set(table))


def test_encrypted_ranges():
    data = synth.build_decrypted((4, 6, 3, 2, 5))
    assert synth.encrypted_ranges(data) == [(4, 10), (13, 15)]


def test_oracle_roundtrip_and_only_encrypted_regions_change():
    plain = synth.build_decrypted((4, 6, 4))
    enc = synth.encrypt(plain)
    assert enc != plain
    assert enc[: 4 * synth.SECTOR] == plain[: 4 * synth.SECTOR]
    assert enc[10 * synth.SECTOR :] == plain[10 * synth.SECTOR :]
    assert synth.decrypt(enc) == plain
