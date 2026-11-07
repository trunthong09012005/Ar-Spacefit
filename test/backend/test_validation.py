"""Boundary tests: invalid scan metadata must never become a reconstruction job."""
import pytest
from pydantic import ValidationError

from spacefit.models import Calibration, ScanMetadata


@pytest.mark.parametrize("patch", [
    {"name": "   "}, {"name": ""}, {"name": "x" * 121},
    {"reference_cm": 0}, {"reference_cm": -1}, {"reference_cm": 1000.01},
    {"reference_cm": float("nan")}, {"reference_cm": float("inf")},
    {"reference_axis": "length"}, {"category": "COMMAND"}, {"extra": True},
])
def test_invalid_metadata_boundaries(patch):
    with pytest.raises(ValidationError):
        ScanMetadata.model_validate(dict(name="Chair", reference_cm=15) | patch)


def test_metadata_trims_name_and_preserves_measured_decimals():
    scan = ScanMetadata(name="  Ghế thật  ", reference_cm=15.25)
    assert scan.name == "Ghế thật"
    assert scan.reference_cm == 15.25
    assert ScanMetadata(name="x", reference_cm=1000).reference_cm == 1000


@pytest.mark.parametrize("patch", [
    {"reviewed": False}, {"reviewed": "true"}, {"reviewed": 1},
    {"rotation_x": 361}, {"rotation_z": float("nan")},
    {"crop_min": [-.1, 0, 0]}, {"crop_max": [1, 1, 1.01]},
    {"crop_min": [0, .5, 0], "crop_max": [1, .5, 1]},
    {"crop_max": [1, 1]}, {"crop_min": [float("nan"), 0, 0]},
])
def test_calibration_requires_explicit_boolean_and_ordered_finite_bounds(patch):
    with pytest.raises(ValidationError):
        Calibration.model_validate(dict(reference_cm=15, reviewed=True) | patch)


def test_boundary_rotations_and_reference_axes_are_valid():
    for axis in ("width", "height", "depth"):
        value = Calibration(reference_axis=axis, reference_cm=.1, reviewed=True,
                            rotation_x=-360, rotation_y=360)
        assert value.reference_axis == axis
