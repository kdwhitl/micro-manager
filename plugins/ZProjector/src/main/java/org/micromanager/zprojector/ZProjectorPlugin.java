///////////////////////////////////////////////////////////////////////////////
//FILE:          ZProjectorPlugin.java
//PROJECT:       Micro-Manager 
//SUBSYSTEM:     ZProjector plugin
//-----------------------------------------------------------------------------
//
// AUTHOR:       Nico Stuurman
//
// COPYRIGHT:    Regents of the University of California 2017
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

package org.micromanager.zprojector;

import org.micromanager.Studio;
import org.micromanager.display.DisplayGearMenuPlugin;
import org.micromanager.display.DisplayWindow;


import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;


/**
 * Plugin that copies (parts of) Micro-Manager datasets to a new datastore
 * @author nico
 */
// replace with:
//@plugin(type=MenuPlugin.class)
// to make the code show up in the gearmenu when running under Netbeans
@Plugin(type = DisplayGearMenuPlugin.class)
public class ZProjectorPlugin implements DisplayGearMenuPlugin, SciJavaPlugin {
   public static final String MENUNAME = "Z Project...";
   private Studio studio_;

   @Override
   public String getSubMenu() {
      return "";
   }

   @Override
   public void onPluginSelected(DisplayWindow display) {
      // no need to hold on to the instance, we just want to create the frame
      ZProjectorPluginExecutor ourFrame = new ZProjectorPluginExecutor(studio_, display);
   }

   @Override
   public void setContext(Studio studio) {
      studio_ = studio;
   }

   @Override
   public String getName() {
      return MENUNAME;
   }

   @Override
   public String getHelpText() {
      return "Generates a ZProjection of a Micro-Manager datasets";
   }

   @Override
   public String getVersion() {
      return "Version 0.1-beta";
   }

   @Override
   public String getCopyright() {
      return "Regents of the University of California, 2017";
   }


}
