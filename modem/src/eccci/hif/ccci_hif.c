// SPDX-License-Identifier: GPL-2.0
/*
 * Copyright (C) 2016 MediaTek Inc.
 */

#include <linux/types.h>
#include <linux/ktime.h>

#include "ccci_debug.h"
#include "ccci_core.h"
#include "ccci_hif.h"
#include "ccci_hif_internal.h"
#define TAG "hif"

void *ccci_hif[CCCI_HIF_NUM];
struct ccci_hif_ops *ccci_hif_op[CCCI_HIF_NUM];

int ccci_dpmaif_empty_query(int qno)
{
	int ret = 0;

	if (ccci_hif[DPMAIF_HIF_ID] && ccci_hif_op[DPMAIF_HIF_ID]->empty_query)
		ret |= ccci_hif_op[DPMAIF_HIF_ID]->empty_query(qno);
	else
		CCCI_ERROR_LOG(-1, TAG, "empty_query is null\n");

	return ret;
}

int ccci_hif_dump_status(unsigned int hif_flag,
		enum MODEM_DUMP_FLAG dump_flag,
		void *buff, int length)
{
	int ret = 0;


	if (hif_flag & (1 << CCIF_HIF_ID) && ccci_hif_op[CCIF_HIF_ID] &&
			ccci_hif_op[CCIF_HIF_ID]->dump_status)
		ret |= ccci_hif_op[CCIF_HIF_ID]->dump_status(CCIF_HIF_ID,
			dump_flag, buff, length);

	if (hif_flag & (1 << DPMAIF_HIF_ID) && ccci_hif_op[DPMAIF_HIF_ID] &&
			ccci_hif_op[DPMAIF_HIF_ID]->dump_status)
		ret |= ccci_hif_op[DPMAIF_HIF_ID]->dump_status(DPMAIF_HIF_ID,
			dump_flag, buff, length);

	return ret;
}

void *ccci_hif_fill_rt_header(unsigned char hif_id,
	int packet_size, unsigned int tx_ch, unsigned int txqno)
{
	if (ccci_hif[hif_id] && ccci_hif_op[hif_id]->fill_rt_header)
		return ccci_hif_op[hif_id]->fill_rt_header(hif_id,
			packet_size, tx_ch, txqno);
	CCCI_ERROR_LOG(-1, CORE, "rt header : %d\n", hif_id);
	return NULL;
}

int ccci_hif_set_wakeup_src(unsigned char hif_id, int value)
{
	int ret = 0;

	switch (hif_id) {
	case CCIF_HIF_ID:
	case DPMAIF_HIF_ID:
		if (ccci_hif[hif_id] && ccci_hif_op[hif_id]->debug)
			ret = ccci_hif_op[hif_id]->debug(hif_id,
				CCCI_HIF_DEBUG_SET_WAKEUP, &value);
		break;
	default:
		break;
	}

	return ret;
}

int ccci_hif_send_skb(unsigned char hif_id, int tx_qno, struct sk_buff *skb,
	int from_pool, int blocking)
{
	int ret = 0;

	switch (hif_id) {
	case CCIF_HIF_ID:
	case DPMAIF_HIF_ID:
		if (ccci_hif[hif_id] && ccci_hif_op[hif_id]->send_skb)
			ret = ccci_hif_op[hif_id]->send_skb(hif_id,
				tx_qno, skb, from_pool, blocking);
		break;
	default:
		break;
	}

	return ret;
}

int ccci_hif_send_data(unsigned char hif_id, int tx_qno)
{
	int ret = 0;

	if (ccci_hif[hif_id] && ccci_hif_op[hif_id]->send_data)
		ret = ccci_hif_op[hif_id]->send_data(hif_id, tx_qno);
	return ret;
}

int ccci_hif_write_room(unsigned char hif_id, unsigned char qno)
{
	int ret = 0;

	switch (hif_id) {
	case CCIF_HIF_ID:
	case DPMAIF_HIF_ID:
		if (ccci_hif[hif_id] && ccci_hif_op[hif_id]->write_room)
			ret = ccci_hif_op[hif_id]->write_room(hif_id, qno);
		break;
	default:
		break;
	}
	return ret;
}

int ccci_hif_ask_more_request(unsigned char hif_id, int rx_qno)
{
	int ret = 0;

	switch (hif_id) {
	case CCIF_HIF_ID:
	case DPMAIF_HIF_ID:
		if (ccci_hif[hif_id] && ccci_hif_op[hif_id]->give_more)
			ret = ccci_hif_op[hif_id]->give_more(hif_id, rx_qno);
		break;
	default:
		break;
	}
	return ret;
}

static inline int ccci_hif_napi_poll(int rx_qno,
	struct napi_struct *napi, int weight)
{
	return 0;
}

static void ccci_md_dump_log_rec(struct ccci_log *log)
{
	u64 ts_nsec = log->tv;
	unsigned long rem_nsec;

	if (ts_nsec == 0)
		return;
	rem_nsec = do_div(ts_nsec, 1000000000);
	if (!log->dropped) {
		CCCI_MEM_LOG(0, CORE,
		"%08X %08X %08X %08X  %5lu.%06lu\n",
		log->msg.data[0], log->msg.data[1],
		*(((u32 *)&log->msg) + 2),
		log->msg.reserved, (unsigned long)ts_nsec, rem_nsec / 1000);
	} else {
		CCCI_MEM_LOG(0, CORE, "%08X %08X %08X %08X  %5lu.%06lu -\n",
			log->msg.data[0], log->msg.data[1],
			*(((u32 *)&log->msg) + 2),
			log->msg.reserved, (unsigned long)ts_nsec,
			rem_nsec / 1000);
	}
}

void ccci_md_add_log_history(struct ccci_hif_traffic *tinfo,
	enum DIRECTION dir,
	int queue_index, struct ccci_header *msg, int is_dropped)
{
#ifdef PACKET_HISTORY_DEPTH
	if (dir == OUT) {
		memcpy(&tinfo->tx_history[queue_index][
			tinfo->tx_history_ptr[queue_index]].msg, msg,
			sizeof(struct ccci_header));
		tinfo->tx_history[queue_index][
			tinfo->tx_history_ptr[queue_index]].tv
			= local_clock();
		tinfo->tx_history[queue_index][
			tinfo->tx_history_ptr[queue_index]].dropped
			= is_dropped;
		tinfo->tx_history_ptr[queue_index]++;
		tinfo->tx_history_ptr[queue_index]
		&= (unsigned int)(PACKET_HISTORY_DEPTH - 1);
	}
	if (dir == IN) {
		memcpy(&tinfo->rx_history[queue_index][
			tinfo->rx_history_ptr[queue_index]].msg, msg,
		sizeof(struct ccci_header));
		tinfo->rx_history[queue_index][
			tinfo->rx_history_ptr[queue_index]].tv = local_clock();
		tinfo->rx_history[queue_index][
			tinfo->rx_history_ptr[queue_index]].dropped =
			is_dropped;
		tinfo->rx_history_ptr[queue_index]++;
		tinfo->rx_history_ptr[queue_index]
		&= (PACKET_HISTORY_DEPTH - 1);
	}
#endif
}
EXPORT_SYMBOL(ccci_md_add_log_history);

void ccci_md_dump_log_history(
	struct ccci_hif_traffic *tinfo, int dump_multi_rec,
	int tx_queue_num, int rx_queue_num)
{
#ifdef PACKET_HISTORY_DEPTH
	int i_tx, i_rx, j;
	int tx_qno, rx_qno;

	if (!dump_multi_rec && (tx_queue_num >= MAX_TXQ_NUM ||
		rx_queue_num >= MAX_RXQ_NUM))
		return;

	if (dump_multi_rec) {
		tx_qno = ((tx_queue_num <= MAX_TXQ_NUM) ?
			tx_queue_num : MAX_TXQ_NUM);
		rx_qno = ((rx_queue_num <= MAX_RXQ_NUM) ?
			rx_queue_num : MAX_RXQ_NUM);
		i_tx = 0;
		i_rx = 0;
	} else {
		tx_qno = tx_queue_num + 1;
		rx_qno = rx_queue_num + 1;
		i_tx = tx_queue_num;
		i_rx = rx_queue_num;
	}

	if (rx_queue_num > 0)
		for (; i_rx < rx_qno; i_rx++) {
			CCCI_MEM_LOG_TAG(0, CORE,
				"dump rxq%d packet history, ptr=%d\n", i_rx,
			       tinfo->rx_history_ptr[i_rx]);
			for (j = 0; j < PACKET_HISTORY_DEPTH; j++)
				ccci_md_dump_log_rec(&tinfo->rx_history[i_rx][j]);
		}
	if (tx_queue_num > 0)
		for (; i_tx < tx_qno; i_tx++) {
			CCCI_MEM_LOG_TAG(0, CORE,
				"dump txq%d packet history, ptr=%d\n", i_tx,
			       tinfo->tx_history_ptr[i_tx]);
			for (j = 0; j < PACKET_HISTORY_DEPTH; j++)
				ccci_md_dump_log_rec(&tinfo->tx_history[i_tx][j]);
		}
#endif
}
EXPORT_SYMBOL(ccci_md_dump_log_history);

int ccci_hif_start(unsigned char hif_id)
{
	int ret = 0;

	if (ccci_hif[hif_id] && ccci_hif_op[hif_id]->start)
		ret |= ccci_hif_op[hif_id]->start(hif_id);

	//if (ccci_hif[CCIF_HIF_ID] && ccci_hif_op[CCIF_HIF_ID]->start)
	//	ret |= ccci_hif_op[CCIF_HIF_ID]->start(CCIF_HIF_ID);
	//if (ccci_hif[DPMAIF_HIF_ID] && ccci_hif_op[DPMAIF_HIF_ID]->start)
	//	ret |= ccci_hif_op[DPMAIF_HIF_ID]->start(DPMAIF_HIF_ID);

	return ret;
}

void ccci_hif_md_exception(unsigned int hif_flag, unsigned char stage)
{
	switch (stage) {
	case HIF_EX_INIT:
		/* eg. stop tx */
		ccci_hif_dump_status(1 << CCIF_HIF_ID, DUMP_FLAG_CCIF |
			DUMP_FLAG_IRQ_STATUS, NULL, 0);
		break;
	case HIF_EX_CLEARQ_DONE:
	case HIF_EX_ALLQ_RESET:
		/* only CLDMA (not on MT6878) had work to do here */
		break;
	case HIF_EX_STOP_EE_NOTIFY:
		if ((hif_flag & (1<<CCIF_HIF_ID)) && ccci_hif[CCIF_HIF_ID]
			&& ccci_hif_op[CCIF_HIF_ID]->stop_for_ee)
			ccci_hif_op[CCIF_HIF_ID]->stop_for_ee(CCIF_HIF_ID);
		break;
	default:
		break;
	};

}

int ccci_hif_state_notification(unsigned char state)
{
	int ret = 0;

	switch (state) {
	case BOOT_WAITING_FOR_HS1:
		ccci_hif_start(CCIF_HIF_ID);
		ccci_hif_start(DPMAIF_HIF_ID);

		break;
	case READY:
		break;
	case RESET:
		break;
	case EXCEPTION:
	case WAITING_TO_STOP:
		if (ccci_hif[DPMAIF_HIF_ID] &&
			ccci_hif_op[DPMAIF_HIF_ID]->pre_stop) {
			ccci_hif_dump_status(1 << DPMAIF_HIF_ID,
				DUMP_FLAG_REG, NULL, -1);
			 ccci_hif_op[DPMAIF_HIF_ID]->pre_stop(DPMAIF_HIF_ID);
		}
		break;
	case GATED:
		if (ccci_hif[CCIF_HIF_ID] && ccci_hif_op[CCIF_HIF_ID]->stop)
			ret |= ccci_hif_op[CCIF_HIF_ID]->stop(CCIF_HIF_ID);
		/* later than ccmni */
		if (ccci_hif[DPMAIF_HIF_ID] &&
			ccci_hif_op[DPMAIF_HIF_ID]->stop) {
			ccci_hif_dump_status(1 << DPMAIF_HIF_ID,
				DUMP_FLAG_REG, NULL, -1);
			ret |= ccci_hif_op[DPMAIF_HIF_ID]->stop(DPMAIF_HIF_ID);
		}
		break;
	default:
		break;
	}
	return ret;
}

void ccci_hif_register(unsigned char hif_id, void *hif_per_data,
	struct ccci_hif_ops *ops)
{
	CCCI_NORMAL_LOG(0, CORE, "hif register: %d\n", hif_id);
	CCCI_HISTORY_TAG_LOG(0, CORE,
			"hif register: %d\n", hif_id);

	if (hif_id < CCCI_HIF_NUM) {
		ccci_hif[hif_id] = hif_per_data;
		ccci_hif_op[hif_id] = ops;
	}
}
EXPORT_SYMBOL(ccci_hif_register);
