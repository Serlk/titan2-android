/* SPDX-License-Identifier: GPL-2.0 */
/*
 * The one platform this stack supports: Unihertz Titan 2, MediaTek MT6878
 * with a gen 6299 modem. The MTK driver read both values from the DT at
 * runtime and carried code for every other combination; they are fixed
 * here and every DT read site only checks that the DT agrees.
 */
#ifndef __CCCI_PLATFORM_H__
#define __CCCI_PLATFORM_H__

#define CCCI_AP_PLAT	6878	/* mddriver: mediatek,ap-plat-info */
#define CCCI_MD_GEN	6299	/* mddriver: mediatek,md-generation */

#endif /* __CCCI_PLATFORM_H__ */
