from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, StrictBool, model_validator


class ScanMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False, str_strip_whitespace=True)
    name: str = Field(min_length=1, max_length=120)
    category: Literal["CHAIR", "SOFA", "TABLE", "BED", "WARDROBE", "LAMP", "BOX"] = "CHAIR"
    reference_axis: Literal["width", "height", "depth"] = "width"
    reference_cm: float = Field(gt=0, le=1000)


class Calibration(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    reference_axis: Literal["width", "height", "depth"] = "width"
    reference_cm: float = Field(gt=0, le=1000)
    rotation_x: float = Field(default=0, ge=-360, le=360)
    rotation_y: float = Field(default=0, ge=-360, le=360)
    rotation_z: float = Field(default=0, ge=-360, le=360)
    # Crop in the ORIGINAL GLB's x/y/z bounding box, before rotating.
    crop_min: tuple[float, float, float] = (0, 0, 0)
    crop_max: tuple[float, float, float] = (1, 1, 1)
    reviewed: StrictBool = False

    @model_validator(mode="after")
    def valid_crop(self):
        if not all(0 <= low < high <= 1 for low, high in zip(self.crop_min, self.crop_max)):
            raise ValueError("Crop bounds must satisfy 0 <= min < max <= 1")
        if not self.reviewed:
            raise ValueError("Confirm the object, background removal, and orientation before calibration")
        return self
