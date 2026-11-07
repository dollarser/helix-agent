package com.helix.core.model

/** A stable failure for a user image that cannot meet its admitted normalization guarantee. */
class ImageReselectionRequiredException : IllegalArgumentException(ModelErrorCode.IMAGE_RESELECT_REQUIRED.name)
