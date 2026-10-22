# MTK bugs found during the refactor (to be fixed in separate commits)

1. `eccci/hif/ccci_dpmaif_drv_com.c` `ccci_drv_dl_add_pit_remain_cnt(unsigned short)`:
   a 0x3FFFF mask on a u16 — the top 2 bits of the PIT counter are always lost (same in
   drv3_dl_add_lro0/1_pit_remain_cnt). The caller passes an unsigned short — check whether
   the counter can exceed 65535.
2. `eccci/fsm/modem_sys1.c` `md_net_speed_show`: passes DPMAIF_HIF_ID (=2) as the
   hif_flag mask → that is the CCIF bit, so DUMP_FLAG_TOGGLE_NET_SPD goes to CCIF.
   Should be `1 << DPMAIF_HIF_ID`.
3. (from userspace RE) FORCE_STOP/RESET: mdinit sends a non-zero flag in DO_STOP_MD, the kernel
   treats it as flight mode — check on a live reset.
