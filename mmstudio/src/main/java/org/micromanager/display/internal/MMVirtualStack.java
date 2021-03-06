///////////////////////////////////////////////////////////////////////////////
//PROJECT:       Micro-Manager
//SUBSYSTEM:     Display implementation
//-----------------------------------------------------------------------------
//
// AUTHOR:       Chris Weisiger, 2015
//
// COPYRIGHT:    University of California, San Francisco, 2015
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.

package org.micromanager.display.internal;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import ij.CompositeImage;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import java.util.ArrayList;
import java.util.HashMap;
import org.micromanager.Studio;
import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.data.internal.DefaultCoords;
import org.micromanager.data.internal.DefaultImage;
import org.micromanager.data.internal.DefaultImageJConverter;
import org.micromanager.data.internal.DefaultMetadata;
import org.micromanager.display.NewImagePlusEvent;
import org.micromanager.display.internal.events.StackPositionChangedEvent;

/**
 * This stack class provides the ImagePlus with images from the Datastore.
 * It is needed to let ImageJ access our data, so that ImageJ tools and plugins
 * can operate on it. Though, since ImageJ only knows about three axes
 * (channel, frame, and slice), it won't be able to access the *entire*
 * dataset if there are other axes (like, say, stage position). It can only
 * ever access an XYZTC volume.
 */
public final class MMVirtualStack extends ij.VirtualStack {
   private final Datastore store_;
   private final EventBus displayBus_;
   private ImagePlus plus_;
   private Coords curCoords_;
   private final HashMap<Integer, Image> channelToLastValidImage_;
   private final Studio studio_;

   public MMVirtualStack(Studio studio, Datastore store, EventBus displayBus,
         ImagePlus plus) {
      studio_ = studio;
      store_ = store;
      displayBus_ = displayBus;
      plus_ = plus;
      displayBus_.register(this);
      curCoords_ = new DefaultCoords.Builder().build();
      channelToLastValidImage_ = new HashMap<Integer, Image>();
   }

   /**
    * Given a flat index number, convert that into a Coords object for our
    * dataset.
    */
   private Coords mapFlatIndexToCoords(int flatIndex) {
      // These coordinates are missing all axes that ImageJ doesn't know 
      // about (e.g. stage position), so we augment curCoords with them and
      // use that for our lookup.
      // If we have no ImagePlus yet to translate this index into something
      // more meaningful, then default to all zeros.
      int channel = -1, z = -1, frame = -1;
      if (plus_ != null) {
         int[] pos3D = plus_.convertIndexToPosition(flatIndex);
         // ImageJ coordinates are 1-indexed.
         channel = pos3D[0] - 1;
         z = pos3D[1] - 1;
         frame = pos3D[2] - 1;
      }
      // Only augment a given axis if it's actually present in our datastore.
      Coords.CoordsBuilder builder = curCoords_.copy();
      Coords axisLengths = store_.getMaxIndices();
      if (axisLengths.getChannel() >= 0) {
         builder.channel(channel);
      }
      if (axisLengths.getZ() >= 0) {
         builder.z(z);
      }
      if (axisLengths.getTime() >= 0) {
         builder.time(frame);
      }
      // Augment all missing axes with zeros.
      ArrayList<String> missingAxes = new ArrayList<String>();
      for (String axis : store_.getAxes()) {
         if (curCoords_.getIndex(axis) == -1) {
            missingAxes.add(axis);
            builder.index(axis, 0);
         }
      }
      // Update curCoords if necessary.
      if (missingAxes.size() > 0) {
         Coords.CoordsBuilder replacement = curCoords_.copy();
         for (String axis : missingAxes) {
            replacement.index(axis, 0);
         }
         setCoords(replacement.build());
      }
      return builder.build();
   }

   /**
    * Retrieve the image at the specified index, which we map into a
    * channel/frame/z offset.
    * Note that we only pay attention to a given offset if we already have
    * an index along that axis (e.g. so that we don't try to ask the
    * Datastore for an image at time=0 when none of the images in the Datastore
    * have a time axis whatsoever).
    */
   private Image getImage(int flatIndex) {
      // Note: index is 1-based.
      if (flatIndex > plus_.getStackSize()) {
         studio_.logs().logError("Stack asked for image at " + flatIndex +
               " that exceeds total of " + plus_.getStackSize() + " images");
         return null;
      }
      Image result = null;
      Coords coords = mapFlatIndexToCoords(flatIndex);
      int channel = coords.getChannel();
      if (store_.hasImage(coords)) {
         result = store_.getImage(coords);
      }
      else {
         // HACK: ImageJ may ask us for images that aren't available yet,
         // for example if a draw attempt happens in-between images for a
         // multichannel Z-stack. For now, we return the most recent image
         // we've received for that channel (if any).
         // TODO: In the future we may want multiple strategies for handling
         // missing images, depending on context.
         if (channel != -1 && channelToLastValidImage_.containsKey(channel)) {
            result = channelToLastValidImage_.get(channel);
         }
         else {
            // We have no images whatsoever for this channel. Unfortunately
            // we *still* can't return null in this case (lest ImageJ break
            // horribly), so we synthesize a new image that matches
            // dimensionality with any existing image.
            result = generateFakeImage(curCoords_);
         }
      }
      if (channel != -1) {
         channelToLastValidImage_.put(channel, result);
      }
      return result;
   }

   /**
    * Generate an image of all zeros that matches the width, height, and
    * bit depth of an existing image in the datastore.
    */
   private Image generateFakeImage(Coords pos) {
      Image tmp = store_.getAnyImage();
      if (tmp == null) {
         studio_.logs().logError("Unable to find any image whatsoever to base a new fake image on.");
         return null;
      }
      int width = tmp.getWidth();
      int height = tmp.getHeight();
      int bytesPerPixel = tmp.getBytesPerPixel();
      int numComponents = tmp.getNumComponents();
      Object rawPixels = tmp.getRawPixels();
      // Replace rawPixels with an appropriately-sized array of the right
      // datatype.
      int size = width * height * numComponents;
      if (rawPixels instanceof byte[]) {
         rawPixels = new byte[size];
         for (int i = 0; i < size; ++i) {
            ((byte[]) rawPixels)[i] = 0;
         }
      }
      else if (rawPixels instanceof short[]) {
         rawPixels = new short[size];
         for (int i = 0; i < size; ++i) {
            ((short[]) rawPixels)[i] = 0;
         }
      }
      else {
         studio_.logs().logError("Unrecognized datatype for image; can't generate new dummy image.");
      }
      return new DefaultImage(rawPixels, width, height, bytesPerPixel,
            numComponents, pos, new DefaultMetadata.Builder().build());
   }

   /**
    * Retrieve the pixel buffer for the image at the specified offset. See
    * getImage() for more details.
    */
   @Override
   public Object getPixels(int flatIndex) {
      if (plus_ == null) {
         studio_.logs().logError("Asked to get pixels when I don't have an ImagePlus");
         return null;
      }
      Image image = getImage(flatIndex);
      if (image != null) {
         if (image.getNumComponents() != 1) {
            // Extract the appropriate component.
            return image.getRawPixelsForComponent((flatIndex - 1) % image.getNumComponents());
         }
         return image.getRawPixels();
      }
      studio_.logs().logError("Null image at " + curCoords_);
      return null;
   }

   @Override
   public ImageProcessor getProcessor(int flatIndex) {
      if (plus_ == null) {
         studio_.logs().logError("Tried to get a processor when there's no ImagePlus");
         return null;
      }
      Image image = getImage(flatIndex);
      if (image == null) {
         studio_.logs().logError("Tried to get a processor for an invalid image index " + flatIndex + " which ImageJ treats as " + mapFlatIndexToCoords(flatIndex));
         return null;
      }
      ImageProcessor result = ((DefaultImageJConverter) studio_.data().ij()).createProcessor(image, false);
      if (result == null) {
         int numPixels = -1;
         String type = "unknown";
         Object pixels = image.getRawPixels();
         if (pixels instanceof byte[]) {
            numPixels = ((byte[]) pixels).length;
            type = "byte[]";
         }
         else if (pixels instanceof short[]) {
            numPixels = ((short[]) pixels).length;
            type = "short[]";
         }
         else if (pixels instanceof int[]) {
            numPixels = ((int[]) pixels).length;
            type = "int[]";
         }
         studio_.logs().logError(String.format("Unable to create ImageProcessor from image of type %s: dimensions (%dx%d) and pixel length is %d", type,
                  image.getWidth(), image.getHeight(), numPixels));
      }
      return result;
   }

   @Override
   public int getSize() {
      // Calculate the total number of "addressable" images along the
      // axes that ImageJ knows about.
      String[] axes = {Coords.CHANNEL, Coords.TIME, Coords.Z};
      int result = 1;
      for (String axis : axes) {
         if (store_.getAxisLength(axis) != 0) {
            result *= store_.getAxisLength(axis);
         }
      }
      return result;
   }

   @Override
   public String getSliceLabel(int n) {
      return Integer.toString(n);
   }

   /**
    * Update the current index we are centered on.
    *
    * This in turn will affect future calls to getPixels(). In the process we
    * also ensure that the ImagePlus object has the right dimensions to
    * encompass these coordinates.
    * TODO Shouldn't this live in MMImagePlus/MMCompositeImage?
    */
   public void setCoords(Coords coords) {
      int numChannels = Math.max(1, store_.getAxisLength(Coords.CHANNEL));
      int numFrames = Math.max(1, store_.getAxisLength(Coords.TIME));
      int numSlices = Math.max(1, store_.getAxisLength(Coords.Z));
      if (plus_ instanceof IMMImagePlus) {
         IMMImagePlus temp = (IMMImagePlus) plus_;
         temp.setNChannelsUnverified(numChannels);
         temp.setNFramesUnverified(numFrames);
         temp.setNSlicesUnverified(numSlices);
      }
      // HACK: if we call this with all 1s then ImageJ will create a new
      // display window in addition to our own (WHY?!), so only call it if
      // we actually have at least 2 images across these axes.
      if (numChannels != 1 || numSlices != 1 || numFrames != 1) {
         plus_.setDimensions(numChannels, numSlices, numFrames);
      }
      int channel = coords.getChannel() + 1;
      int z = coords.getZ() + 1;
      int time = coords.getTime() + 1;
      boolean isCompositeMode = (plus_.isComposite() &&
            ((CompositeImage) plus_).getMode() == CompositeImage.COMPOSITE);
      if (z != plus_.getSlice() || time != plus_.getFrame() ||
            (!isCompositeMode && channel != plus_.getChannel())) {
         plus_.setPosition(channel, z, time);
      }
      // HACK: if we're in a composite view mode and any non-Z/Time/Channel
      // axis has changed, ImageJ doesn't know about it and won't bother
      // redrawing, so we'll need to force a redraw.
      boolean mustForceRedraw = false;
      if (isCompositeMode) {
         for (String axis : coords.getAxes()) {
            if (!axis.equals(Coords.CHANNEL) && !axis.equals(Coords.TIME) &&
                  !axis.equals(Coords.Z) &&
                  coords.getIndex(axis) != curCoords_.getIndex(axis)) {
               mustForceRedraw = true;
               break;
            }
         }
      }
      curCoords_ = coords;
      if (mustForceRedraw) {
         // Manually grab the pixels for each channel and set them into the
         // corresponding ImageProcessor.
         for (int i = 0; i < numChannels; ++i) {
            // ImageJ uses 1-indexing...
            ImageProcessor proc = ((CompositeImage) plus_).getProcessor(i + 1);
            Object pixels = getPixels(
                  plus_.getCurrentSlice() - plus_.getChannel() + i + 1);
            proc.setPixels(pixels);
         }
      }
      displayBus_.post(new StackPositionChangedEvent(curCoords_));
   }

   /**
    * Return our current coordinates.
    */
   public Coords getCurrentImageCoords() {
      return curCoords_;
   }

   @Subscribe
   public void onNewImagePlus(NewImagePlusEvent event) {
      plus_ = event.getImagePlus();
   }

   // TODO Remove this after removing sole use in Projector
   public Datastore getDatastore() {
      return store_;
   }
}
